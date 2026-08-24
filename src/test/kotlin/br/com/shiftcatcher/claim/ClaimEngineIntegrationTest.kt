package br.com.shiftcatcher.claim

import br.com.shiftcatcher.PostgresTestConfiguration
import br.com.shiftcatcher.foundation.http.ApiProblemException
import br.com.shiftcatcher.integration.greenapi.GreenApiFailureKind
import br.com.shiftcatcher.integration.greenapi.GreenApiInstanceHealth
import br.com.shiftcatcher.integration.greenapi.GreenApiInstanceState
import br.com.shiftcatcher.integration.greenapi.GreenApiTransportException
import br.com.shiftcatcher.integration.greenapi.WhatsAppInstanceHealth
import br.com.shiftcatcher.reliability.ClaimOutboxProcessor
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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Import(PostgresTestConfiguration::class, ClaimEngineIntegrationTest.FakeProviderConfiguration::class)
@SpringBootTest(
    webEnvironment = WebEnvironment.MOCK,
    properties = [
        "shift-catcher.security.admin-api-token=test-admin-token",
        "shift-catcher.green-api.instance-id=123456",
        "shift-catcher.green-api.webhook-token=test-webhook-token",
        "shift-catcher.detection.known-locations[0]=PS Central",
        // The scheduler is off so the tests drain the outbox deterministically instead of racing it.
        "shift-catcher.claim.worker-enabled=false",
        "shift-catcher.claim.retry-delays-ms[0]=0",
        "shift-catcher.claim.retry-delays-ms[1]=0",
        "shift-catcher.claim.retry-delays-ms[2]=0",
    ],
)
@AutoConfigureMockMvc
class ClaimEngineIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val sender: FakeMessageSender,
    @Autowired private val instanceHealth: SettableInstanceHealth,
    @Autowired private val claimService: ClaimService,
    @Autowired private val processor: ClaimOutboxProcessor,
) {
    class SettableInstanceHealth : WhatsAppInstanceHealth {
        @Volatile
        var operational: Boolean = true

        override fun getState(): GreenApiInstanceHealth {
            val state =
                if (operational) GreenApiInstanceState.AUTHORIZED else GreenApiInstanceState.NOT_AUTHORIZED
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
        fun settableInstanceHealth(): SettableInstanceHealth = SettableInstanceHealth()
    }

    @BeforeEach
    fun reset() {
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
        sender.reset()
        instanceHealth.operational = true
    }

    @Test
    fun `claim endpoints require the admin bearer token`() {
        mockMvc.get("/api/v1/claims").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `deciding a claim writes the intent without sending anything`() {
        val opportunityId = anEligibleOpportunity()

        postClaim(opportunityId).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CREATED") }
            jsonPath("$.mode") { value("MANUAL") }
            jsonPath("$.message") { value("PEGO") }
            jsonPath("$.quotedMessageId") { value("claim-message-1") }
        }

        // DEC-006: the claim and the send intent are one transaction; the send is not.
        assertEquals(0, sender.calls.get())
        assertEquals("CLAIM_PENDING", opportunityStatus())
        assertEquals("PENDING", jdbcTemplate.queryForObject("select status from outbox_event", String::class.java))
    }

    @Test
    fun `the worker sends one quoted PEGO and completes the claim`() {
        val opportunityId = anEligibleOpportunity()
        postClaim(opportunityId).andExpect { status { isOk() } }

        assertEquals(1, processor.processDueEvents())

        assertEquals(1, sender.calls.get())
        val sent = sender.sends.single()
        assertEquals(GROUP_CHAT_ID, sent.chatId)
        assertEquals("claim-message-1", sent.quotedMessageId)
        assertEquals("PEGO", sent.message)

        assertEquals("CLAIMED", claimStatus())
        assertEquals("CLAIMED", opportunityStatus())
        assertEquals("DONE", jdbcTemplate.queryForObject("select status from outbox_event", String::class.java))
        val attempt = jdbcTemplate.queryForMap("select result, provider_response_id, latency_ms from claim_attempt")
        assertEquals("ACCEPTED", attempt["result"])
        assertNotNull(attempt["provider_response_id"])
        assertNotNull(attempt["latency_ms"])
    }

    @Test
    fun `concurrent claims leave one winner and one logical send`() {
        val opportunityId = anEligibleOpportunity()
        val workers = 8
        val barrier = CyclicBarrier(workers)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val outcomes =
                (1..workers)
                    .map {
                        executor.submit<Boolean> {
                            barrier.await(5, TimeUnit.SECONDS)
                            runCatching { claimService.claim(opportunityId, null) }
                                .fold(onSuccess = { true }, onFailure = { failure ->
                                    if (failure is ApiProblemException) false else throw failure
                                })
                        }
                    }.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, outcomes.count { it }, "exactly one claim may win")
            assertEquals(1, countOf("shift_claim"))
            assertEquals(1, countOf("outbox_event"))
        } finally {
            executor.shutdownNow()
        }

        processor.processDueEvents()
        assertEquals(1, sender.calls.get(), "one opportunity is one logical WhatsApp message")
    }

    @Test
    fun `draining the outbox twice does not send twice`() {
        val opportunityId = anEligibleOpportunity()
        postClaim(opportunityId).andExpect { status { isOk() } }

        processor.processDueEvents()
        processor.processDueEvents()

        assertEquals(1, sender.calls.get())
    }

    @Test
    fun `the quote is frozen when the claim is decided`() {
        val opportunityId = anEligibleOpportunity()
        postClaim(opportunityId).andExpect { status { isOk() } }

        // Whatever happens to the stored message afterwards, the worker must quote what was decided.
        jdbcTemplate.update("update incoming_message set provider_message_id = 'tampered'")
        processor.processDueEvents()

        assertEquals("claim-message-1", sender.sends.single().quotedMessageId)
    }

    @Test
    fun `a transient failure is retried and then gives up`() {
        val opportunityId = anEligibleOpportunity()
        sender.failure = GreenApiTransportException(GreenApiFailureKind.TIMEOUT, "timed out")
        postClaim(opportunityId).andExpect { status { isOk() } }

        processor.processDueEvents()

        assertEquals(3, sender.calls.get(), "the configured retry budget is three attempts")
        assertEquals("FAILED", claimStatus())
        assertEquals("CLAIM_FAILED", opportunityStatus())
        assertEquals(3, countOf("claim_attempt"))
        assertEquals(
            "TRANSIENT_FAILURE",
            jdbcTemplate.queryForObject(
                "select result from claim_attempt order by attempt_number limit 1",
                String::class.java,
            ),
        )
    }

    @Test
    fun `a rejected request is not retried`() {
        val opportunityId = anEligibleOpportunity()
        sender.failure = GreenApiTransportException(GreenApiFailureKind.CLIENT_ERROR, "rejected")
        postClaim(opportunityId).andExpect { status { isOk() } }

        processor.processDueEvents()

        assertEquals(1, sender.calls.get(), "retrying a rejected request only repeats the rejection")
        assertEquals("FAILED", claimStatus())
        assertEquals(
            "PERMANENT_FAILURE",
            jdbcTemplate.queryForObject("select result from claim_attempt", String::class.java),
        )
    }

    @Test
    fun `a transient failure that clears is claimed on a later attempt`() {
        val opportunityId = anEligibleOpportunity()
        sender.failure = GreenApiTransportException(GreenApiFailureKind.SERVER_ERROR, "boom")
        sender.failuresBeforeSuccess = 1
        postClaim(opportunityId).andExpect { status { isOk() } }

        processor.processDueEvents()

        assertEquals(2, sender.calls.get())
        assertEquals("CLAIMED", claimStatus())
        assertEquals("CLAIMED", opportunityStatus())
    }

    @Test
    fun `a manual retry reuses the same claim and sends once`() {
        val opportunityId = anEligibleOpportunity()
        sender.failure = GreenApiTransportException(GreenApiFailureKind.TIMEOUT, "timed out")
        postClaim(opportunityId).andExpect { status { isOk() } }
        processor.processDueEvents()
        assertEquals("FAILED", claimStatus())
        val callsBefore = sender.calls.get()

        sender.failure = null
        val claimId = jdbcTemplate.queryForObject("select id from shift_claim", UUID::class.java).toString()
        mockMvc.post("/api/v1/claims/$claimId/retry") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("RETRY_PENDING") }
        }
        processor.processDueEvents()

        assertEquals("CLAIMED", claimStatus())
        assertEquals(1, countOf("shift_claim"), "a retry never creates a second claim")
        assertEquals(1, countOf("outbox_event"), "a retry never creates a second send intent")
        assertEquals(callsBefore + 1, sender.calls.get())
    }

    @Test
    fun `a claimed claim cannot be retried`() {
        val opportunityId = anEligibleOpportunity()
        postClaim(opportunityId).andExpect { status { isOk() } }
        processor.processDueEvents()
        val claimId = jdbcTemplate.queryForObject("select id from shift_claim", UUID::class.java).toString()

        mockMvc.post("/api/v1/claims/$claimId/retry") { adminBearer() }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONFLICT") }
        }
    }

    @Test
    fun `an opportunity that is not eligible cannot be claimed`() {
        val opportunityId = anOpportunityInReview()

        postClaim(opportunityId).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("OPPORTUNITY_NOT_CLAIMABLE") }
        }
        assertEquals(0, countOf("shift_claim"))
    }

    @Test
    fun `auto mode is refused unless the evaluation allowed it`() {
        val opportunityId = anEligibleOpportunity()

        mockMvc
            .post("/api/v1/opportunities/$opportunityId/claim") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"mode":"AUTO"}"""
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("OPPORTUNITY_NOT_CLAIMABLE") }
            }

        assertEquals(0, countOf("shift_claim"))
        assertEquals("ELIGIBLE", opportunityStatus(), "a refused claim leaves the opportunity untouched")
    }

    @Test
    fun `a non operational instance blocks the claim before anything is written`() {
        val opportunityId = anEligibleOpportunity()
        instanceHealth.operational = false

        postClaim(opportunityId).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("INSTANCE_NOT_OPERATIONAL") }
        }

        assertEquals(0, countOf("shift_claim"))
        assertEquals(0, countOf("outbox_event"))
        assertEquals("ELIGIBLE", opportunityStatus())
        assertEquals(0, sender.calls.get())
    }

    @Test
    fun `claiming twice is a conflict`() {
        val opportunityId = anEligibleOpportunity()
        postClaim(opportunityId).andExpect { status { isOk() } }

        postClaim(opportunityId).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONFLICT") }
        }
        assertEquals(1, countOf("shift_claim"))
    }

    @Test
    fun `listing and detail expose the attempts`() {
        val opportunityId = anEligibleOpportunity()
        postClaim(opportunityId).andExpect { status { isOk() } }
        processor.processDueEvents()
        val claimId = jdbcTemplate.queryForObject("select id from shift_claim", UUID::class.java).toString()

        mockMvc.get("/api/v1/claims") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(1) }
            jsonPath("$.claims[0].status") { value("CLAIMED") }
        }
        mockMvc.get("/api/v1/claims/$claimId") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.attempts.length()") { value(1) }
            jsonPath("$.attempts[0].result") { value("ACCEPTED") }
            jsonPath("$.providerMessageId") { exists() }
        }
        mockMvc.get("/api/v1/claims/${UUID.randomUUID()}") { adminBearer() }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    @Test
    fun `an unknown opportunity cannot be claimed`() {
        postClaim(UUID.randomUUID().toString()).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    private fun anEligibleOpportunity(): String {
        val opportunityId = ingestOffer()
        activatePermissiveRuleSet()
        mockMvc.post("/api/v1/opportunities/$opportunityId/reevaluate") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.result") { value("ELIGIBLE") }
        }
        return opportunityId
    }

    private fun anOpportunityInReview(): String {
        val opportunityId = ingestOffer(text = "tem vaga de plantao alguem quer", providerMessageId = "vague-1")
        assertEquals(
            "REVIEW_REQUIRED",
            jdbcTemplate.queryForObject(
                "select status from shift_opportunity where id = ?::uuid",
                String::class.java,
                opportunityId,
            ),
        )
        return opportunityId
    }

    private fun activatePermissiveRuleSet() {
        val created =
            mockMvc
                .post("/api/v1/rule-sets") {
                    adminBearer()
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"permissivo","definition":{}}"""
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString
        val ruleSetId = JsonPath.read<String>(created, "$.id")
        mockMvc.post("/api/v1/rule-sets/$ruleSetId/activate") { adminBearer() }.andExpect { status { isOk() } }
    }

    private fun ingestOffer(
        text: String = "Plantao amanha 19-07 no PS Central R\$ 1.200",
        providerMessageId: String = "claim-message-1",
    ): String {
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
        return jdbcTemplate
            .queryForObject(
                """
                select o.id
                  from shift_opportunity o
                  join incoming_message m on m.id = o.source_message_id
                 where m.provider_message_id = ?
                """.trimIndent(),
                UUID::class.java,
                providerMessageId,
            ).toString()
    }

    private fun postClaim(opportunityId: String): ResultActionsDsl =
        mockMvc.post("/api/v1/opportunities/$opportunityId/claim") { adminBearer() }

    private fun claimStatus(): String? = jdbcTemplate.queryForObject("select status from shift_claim", String::class.java)

    private fun opportunityStatus(): String? = jdbcTemplate.queryForObject("select status from shift_opportunity", String::class.java)

    private fun countOf(table: String): Int = jdbcTemplate.queryForObject("select count(*) from $table", Int::class.java) ?: 0

    private fun MockHttpServletRequestDsl.adminBearer() {
        header("Authorization", "Bearer test-admin-token")
    }

    private companion object {
        const val GROUP_CHAT_ID = "120363000000000000@g.us"
    }
}
