package br.com.shiftcatcher.reliability

import br.com.shiftcatcher.PostgresTestConfiguration
import br.com.shiftcatcher.claim.ClaimService
import br.com.shiftcatcher.claim.FakeMessageSender
import br.com.shiftcatcher.integration.greenapi.GreenApiInstanceHealth
import br.com.shiftcatcher.integration.greenapi.GreenApiInstanceState
import br.com.shiftcatcher.integration.greenapi.WhatsAppInstanceHealth
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(PostgresTestConfiguration::class, ReliabilityIntegrationTest.FakeProviderConfiguration::class)
@SpringBootTest(
    webEnvironment = WebEnvironment.MOCK,
    properties = [
        "shift-catcher.security.admin-api-token=test-admin-token",
        "shift-catcher.green-api.instance-id=123456",
        "shift-catcher.green-api.webhook-token=test-webhook-token",
        "shift-catcher.detection.known-locations[0]=PS Central",
        "shift-catcher.claim.worker-enabled=false",
        "shift-catcher.claim.retry-delays-ms[0]=0",
        "shift-catcher.claim.health-freshness-seconds=300",
        "shift-catcher.claim.auto-claim-enabled=true",
    ],
)
@AutoConfigureMockMvc
class ReliabilityIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val sender: FakeMessageSender,
    @Autowired private val instanceHealth: ToggleableInstanceHealth,
    @Autowired private val processor: ClaimOutboxProcessor,
    @Autowired private val healthGate: ProviderHealthGate,
    @Autowired private val autoClaimTrigger: AutoClaimTrigger,
    @Autowired private val autoEvaluationTrigger: br.com.shiftcatcher.rules.AutoEvaluationTrigger,
    @Autowired private val claimService: ClaimService,
) {
    class ToggleableInstanceHealth : WhatsAppInstanceHealth {
        val calls =
            java.util.concurrent.atomic
                .AtomicInteger()

        @Volatile
        var operational: Boolean = true

        @Volatile
        var unreachable: Boolean = false

        override fun getState(): GreenApiInstanceHealth {
            calls.incrementAndGet()
            if (unreachable) throw IllegalStateException("provider unreachable")
            val state =
                if (operational) GreenApiInstanceState.AUTHORIZED else GreenApiInstanceState.SLEEP_MODE
            return GreenApiInstanceHealth(state = state, rawState = state.name, observedAt = Instant.now())
        }
    }

    @TestConfiguration
    class FakeProviderConfiguration {
        @Bean
        @Primary
        fun fakeMessageSender(): FakeMessageSender = FakeMessageSender()

        @Bean
        @Primary
        fun toggleableInstanceHealth(): ToggleableInstanceHealth = ToggleableInstanceHealth()
    }

    @BeforeEach
    fun reset() {
        jdbcTemplate.update("delete from audit_event")
        jdbcTemplate.update("delete from claim_attempt")
        jdbcTemplate.update("delete from outbox_event")
        jdbcTemplate.update("delete from shift_claim")
        jdbcTemplate.update("delete from rule_evaluation")
        jdbcTemplate.update("delete from rule_set")
        jdbcTemplate.update("delete from shift_opportunity")
        jdbcTemplate.update("delete from detection_result")
        jdbcTemplate.update("delete from incoming_message")
        jdbcTemplate.update("delete from incoming_provider_event")
        jdbcTemplate.update("delete from allowed_group")
        jdbcTemplate.update("delete from provider_health")
        sender.reset()
        instanceHealth.operational = true
        instanceHealth.unreachable = false
        instanceHealth.calls.set(0)
    }

    @Test
    fun `an unreachable provider is recorded as not operational`() {
        instanceHealth.unreachable = true

        val observation = healthGate.refresh()

        assertEquals("UNKNOWN", observation.state)
        assertEquals(false, observation.operational, "not knowing is never permission to act")
        assertEquals(1, observation.consecutiveFailures)
        assertEquals(false, healthGate.isOperational())
    }

    @Test
    fun `a fresh observation is reused instead of asking the provider again`() {
        healthGate.refresh()
        val callsAfterFirst = observedCalls()

        repeat(5) { healthGate.isOperational() }

        assertEquals(callsAfterFirst, observedCalls(), "the provider rate-limits this call")
    }

    @Test
    fun `the automatic path claims only when every switch is on`() {
        anEligibleAutoClaimableOpportunity()
        healthGate.refresh()

        val summary = autoClaimTrigger.runOnce()

        assertEquals(1, summary.considered)
        assertEquals(1, summary.claimed)
        assertEquals("AUTO", jdbcTemplate.queryForObject("select mode from shift_claim", String::class.java))
        processor.processDueEvents()
        assertEquals(1, sender.calls.get())
    }

    @Test
    fun `a non operational provider blocks the automatic path`() {
        anEligibleAutoClaimableOpportunity()
        instanceHealth.operational = false
        healthGate.refresh()

        val summary = autoClaimTrigger.runOnce()

        assertEquals("PROVIDER_NOT_OPERATIONAL", summary.skippedReason)
        assertEquals(0, countOf("shift_claim"))
        assertEquals(0, sender.calls.get())
    }

    @Test
    fun `a stale observation is refreshed rather than trusted`() {
        anEligibleAutoClaimableOpportunity()
        healthGate.refresh()
        jdbcTemplate.update("update provider_health set observed_at = current_timestamp - interval '1 hour'")
        val before = instanceHealth.calls.get()

        val summary = autoClaimTrigger.runOnce()

        assertEquals(before + 1, instanceHealth.calls.get(), "stale good news is re-checked, not trusted")
        assertEquals(1, summary.claimed)
    }

    @Test
    fun `a stale observation that cannot be refreshed blocks the automatic path`() {
        anEligibleAutoClaimableOpportunity()
        healthGate.refresh()
        jdbcTemplate.update("update provider_health set observed_at = current_timestamp - interval '1 hour'")
        instanceHealth.unreachable = true

        val summary = autoClaimTrigger.runOnce()

        assertEquals("PROVIDER_NOT_OPERATIONAL", summary.skippedReason)
        assertEquals(0, countOf("shift_claim"))
        assertEquals(0, sender.calls.get())
    }

    @Test
    fun `finding work with no observation refreshes on demand instead of waiting a tick`() {
        anEligibleAutoClaimableOpportunity()
        assertEquals(0, instanceHealth.calls.get(), "nothing observed yet")

        val summary = autoClaimTrigger.runOnce()

        assertEquals(1, instanceHealth.calls.get(), "the trigger asked because there was work")
        assertEquals(1, summary.claimed)
    }

    @Test
    fun `an idle pass never touches the provider or the audit trail`() {
        // The old order asked on every tick, burning the provider's rate limit and writing an audit
        // row a second for nothing.
        val summary = autoClaimTrigger.runOnce()

        assertEquals(0, summary.considered)
        assertEquals(null, summary.skippedReason)
        assertEquals(0, instanceHealth.calls.get())
        assertEquals(0, countOf("audit_event"))
    }

    @Test
    fun `a failed observation is retried within seconds instead of a whole cycle`() {
        instanceHealth.unreachable = true
        healthGate.refresh()
        val afterFailure = instanceHealth.calls.get()

        // A good observation is trusted for a minute; a failed one is due again almost immediately.
        jdbcTemplate.update("update provider_health set observed_at = current_timestamp - interval '6 seconds'")
        instanceHealth.unreachable = false
        val recovered = healthGate.refreshIfDue()

        assertEquals(afterFailure + 1, instanceHealth.calls.get())
        assertTrue(recovered.operational)
    }

    @Test
    fun `automatic evaluation promotes what detection parked in EVALUATING`() {
        ingestOffer()
        activateRuleSet("""{"name":"permissivo","definition":{}}""")
        assertEquals(
            "EVALUATING",
            jdbcTemplate.queryForObject("select status from shift_opportunity", String::class.java),
            "detection stops here; the webhook contract forbids rules in the request",
        )

        val evaluated = autoEvaluationTrigger.runOnce()

        assertEquals(1, evaluated)
        assertEquals(
            "ELIGIBLE",
            jdbcTemplate.queryForObject("select status from shift_opportunity", String::class.java),
        )
        assertEquals(0, sender.calls.get(), "evaluating decides, it never sends")
    }

    @Test
    fun `automatic evaluation leaves opportunities waiting for a human alone`() {
        // A vague offer lands in REVIEW_REQUIRED; re-running rules over it would overwrite a verdict
        // nobody has answered yet.
        mockMvc.post("/api/v1/groups") {
            adminBearer()
            contentType = MediaType.APPLICATION_JSON
            content = """{"providerChatId":"$GROUP_CHAT_ID","displayName":"Plantoes"}"""
        }
        postWebhookText("tem vaga de plantao alguem quer", "vague-auto-1")
        activateRuleSet("""{"name":"permissivo","definition":{}}""")

        assertEquals(0, autoEvaluationTrigger.runOnce())
        assertEquals(
            "REVIEW_REQUIRED",
            jdbcTemplate.queryForObject("select status from shift_opportunity", String::class.java),
        )
    }

    @Test
    fun `an opportunity the rules did not approve is never picked up automatically`() {
        anEligibleOpportunity()
        healthGate.refresh()

        val summary = autoClaimTrigger.runOnce()

        assertEquals(0, summary.considered, "autoClaimAllowed was false on the stored evaluation")
        assertEquals(0, countOf("shift_claim"))
    }

    @Test
    fun `restart recovery finishes a send that was interrupted mid-flight`() {
        val opportunityId = anEligibleOpportunity()
        postClaim(opportunityId)
        // Simulate a crash after the event was leased but before the send completed.
        jdbcTemplate.update(
            "update outbox_event set status = 'PROCESSING', locked_until = current_timestamp - interval '5 minutes'",
        )
        jdbcTemplate.update("update shift_claim set status = 'SENDING'")

        assertEquals(1, processor.processDueEvents())

        assertEquals(1, sender.calls.get())
        assertEquals("CLAIMED", claimStatus())
        assertEquals("DONE", jdbcTemplate.queryForObject("select status from outbox_event", String::class.java))
    }

    @Test
    fun `a lease that has not expired is left alone`() {
        val opportunityId = anEligibleOpportunity()
        postClaim(opportunityId)
        jdbcTemplate.update(
            "update outbox_event set status = 'PROCESSING', locked_until = current_timestamp + interval '5 minutes'",
        )

        assertEquals(0, processor.processDueEvents(), "another worker still holds the lease")
        assertEquals(0, sender.calls.get())
    }

    @Test
    fun `the latency endpoint reports percentiles for the whole pipeline`() {
        val opportunityId = anEligibleOpportunity()
        postClaim(opportunityId)
        processor.processDueEvents()

        mockMvc.get("/api/v1/metrics/latency") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.counters.webhooks") { value(1) }
            jsonPath("$.counters.messages") { value(1) }
            jsonPath("$.counters.candidates") { value(1) }
            jsonPath("$.counters.opportunities") { value(1) }
            jsonPath("$.counters.claims") { value(1) }
            jsonPath("$.counters.claimed") { value(1) }
            jsonPath("$.counters.attempts") { value(1) }
            jsonPath("$.counters.retries") { value(0) }
            jsonPath("$.detection.samples") { value(1) }
            jsonPath("$.detection.p95Ms") { exists() }
            jsonPath("$.internalClaim.samples") { value(1) }
            jsonPath("$.internalClaim.p50Ms") { exists() }
            jsonPath("$.generatedAt") { exists() }
        }
    }

    @Test
    fun `the latency endpoint answers on an empty database`() {
        mockMvc.get("/api/v1/metrics/latency") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.counters.webhooks") { value(0) }
            jsonPath("$.internalClaim.samples") { value(0) }
            jsonPath("$.internalClaim.p50Ms") { value(null) }
            jsonPath("$.providerState") { value("UNOBSERVED") }
            jsonPath("$.providerOperational") { value(false) }
        }
    }

    @Test
    fun `the latency endpoint reports the provider state without calling the provider`() {
        healthGate.refresh()
        val callsBefore = observedCalls()

        mockMvc.get("/api/v1/metrics/latency") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.providerState") { value("AUTHORIZED") }
            jsonPath("$.providerOperational") { value(true) }
            jsonPath("$.providerObservationFresh") { value(true) }
        }

        assertEquals(callsBefore, observedCalls(), "reading metrics must not consume the quota")
    }

    @Test
    fun `the latency endpoint requires the admin bearer token`() {
        mockMvc.get("/api/v1/metrics/latency").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the audit trail records what the automatic path decided not to do`() {
        anEligibleAutoClaimableOpportunity()

        autoClaimTrigger.runOnce()

        val events = jdbcTemplate.queryForList("select event_type, detail from audit_event")
        assertTrue(events.isNotEmpty(), "a blocked pass must leave a trace")
    }

    private fun observedCalls(): Int = instanceHealth.calls.get()

    private fun anEligibleOpportunity(): String {
        val opportunityId = ingestOffer()
        activateRuleSet("""{"name":"permissivo","definition":{}}""")
        mockMvc.post("/api/v1/opportunities/$opportunityId/reevaluate") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.result") { value("ELIGIBLE") }
        }
        return opportunityId
    }

    /** Eligible *and* approved for the automatic path by both switches the rule engine checks. */
    private fun anEligibleAutoClaimableOpportunity(): String {
        val opportunityId = ingestOffer()
        val groupId = jdbcTemplate.queryForObject("select id from allowed_group", UUID::class.java).toString()
        mockMvc.post("/api/v1/groups/$groupId/auto-claim/enable") { adminBearer() }.andExpect { status { isOk() } }
        activateRuleSet("""{"name":"auto","definition":{"autoClaimEnabled":true}}""")
        mockMvc.post("/api/v1/opportunities/$opportunityId/reevaluate") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.autoClaimAllowed") { value(true) }
        }
        return opportunityId
    }

    private fun activateRuleSet(body: String) {
        val created =
            mockMvc
                .post("/api/v1/rule-sets") {
                    adminBearer()
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString
        val ruleSetId = JsonPath.read<String>(created, "$.id")
        mockMvc.post("/api/v1/rule-sets/$ruleSetId/activate") { adminBearer() }.andExpect { status { isOk() } }
    }

    private fun ingestOffer(): String {
        mockMvc.post("/api/v1/groups") {
            adminBearer()
            contentType = MediaType.APPLICATION_JSON
            content = """{"providerChatId":"$GROUP_CHAT_ID","displayName":"Plantoes"}"""
        }
        mockMvc
            .post("/api/v1/webhooks/green-api") {
                header("Authorization", "Bearer test-webhook-token")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "typeWebhook": "incomingMessageReceived",
                      "instanceData": {"idInstance": 123456},
                      "timestamp": 1787608800,
                      "idMessage": "reliability-message-1",
                      "senderData": {
                        "chatId": "$GROUP_CHAT_ID",
                        "chatName": "Plantoes",
                        "sender": "5511999999999@c.us",
                        "senderName": "Pessoa"
                      },
                      "messageData": {
                        "typeMessage": "textMessage",
                        "textMessageData": {"textMessage": "Plantao amanha 19-07 no PS Central R$ 1.200"}
                      }
                    }
                    """.trimIndent()
            }.andExpect { status { isOk() } }
        return jdbcTemplate.queryForObject("select id from shift_opportunity", UUID::class.java).toString()
    }

    private fun postWebhookText(
        text: String,
        providerMessageId: String,
    ) {
        mockMvc
            .post("/api/v1/webhooks/green-api") {
                header("Authorization", "Bearer test-webhook-token")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "typeWebhook": "incomingMessageReceived",
                      "instanceData": {"idInstance": 123456},
                      "timestamp": 1787608800,
                      "idMessage": "$providerMessageId",
                      "senderData": {
                        "chatId": "$GROUP_CHAT_ID",
                        "chatName": "Plantoes",
                        "sender": "5511999999999@c.us",
                        "senderName": "Pessoa"
                      },
                      "messageData": {
                        "typeMessage": "textMessage",
                        "textMessageData": {"textMessage": "$text"}
                      }
                    }
                    """.trimIndent()
            }.andExpect { status { isOk() } }
    }

    private fun postClaim(opportunityId: String) {
        mockMvc.post("/api/v1/opportunities/$opportunityId/claim") { adminBearer() }.andExpect { status { isOk() } }
    }

    private fun claimStatus(): String? = jdbcTemplate.queryForObject("select status from shift_claim", String::class.java)

    private fun countOf(table: String): Int = jdbcTemplate.queryForObject("select count(*) from $table", Int::class.java) ?: 0

    private fun MockHttpServletRequestDsl.adminBearer() {
        header("Authorization", "Bearer test-admin-token")
    }

    private companion object {
        const val GROUP_CHAT_ID = "120363000000000000@g.us"
    }
}
