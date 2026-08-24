package br.com.shiftcatcher.availability

import br.com.shiftcatcher.PostgresTestConfiguration
import br.com.shiftcatcher.claim.FakeMessageSender
import br.com.shiftcatcher.integration.greenapi.GreenApiInstanceHealth
import br.com.shiftcatcher.integration.greenapi.GreenApiInstanceState
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The agenda conflict rule of `12-MVP/MVP-Scope.md`, end to end.
 *
 * The rule is checked *before* the claim, as an ordinary hard rule, because the query is local and
 * measured in milliseconds. Claim-first-verify-later only pays for checks that are slow.
 */
@Import(PostgresTestConfiguration::class, AvailabilityIntegrationTest.FakeProviderConfiguration::class)
@SpringBootTest(
    webEnvironment = WebEnvironment.MOCK,
    properties = [
        "shift-catcher.security.admin-api-token=test-admin-token",
        "shift-catcher.green-api.instance-id=123456",
        "shift-catcher.green-api.webhook-token=test-webhook-token",
        "shift-catcher.detection.known-locations[0]=PS Central",
        "shift-catcher.claim.worker-enabled=false",
        "shift-catcher.claim.retry-delays-ms[0]=0",
        "shift-catcher.claim.health-freshness-seconds=0",
    ],
)
@AutoConfigureMockMvc
class AvailabilityIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val sender: FakeMessageSender,
    @Autowired private val processor: ClaimOutboxProcessor,
) {
    @TestConfiguration
    class FakeProviderConfiguration {
        @Bean
        @Primary
        fun fakeMessageSender(): FakeMessageSender = FakeMessageSender()

        @Bean
        @Primary
        fun alwaysAuthorized(): WhatsAppInstanceHealth =
            object : WhatsAppInstanceHealth {
                override fun getState(): GreenApiInstanceHealth =
                    GreenApiInstanceHealth(
                        state = GreenApiInstanceState.AUTHORIZED,
                        rawState = GreenApiInstanceState.AUTHORIZED.name,
                        observedAt = Instant.now(),
                    )
            }
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
        jdbcTemplate.update("delete from availability_entry")
        jdbcTemplate.update("delete from provider_health")
        sender.reset()
    }

    @Test
    fun `availability endpoints require the admin bearer token`() {
        mockMvc.get("/api/v1/availability").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a hand-kept entry is listed and can be deleted`() {
        val entryId = addEntry(LocalDate.of(2026, 9, 3), "07:00", "13:00", label = "Santa Casa")

        mockMvc
            .get("/api/v1/availability") {
                adminBearer()
                param("from", "2026-09-01")
                param("to", "2026-09-30")
            }.andExpect {
                status { isOk() }
                jsonPath("$.count") { value(1) }
                jsonPath("$.commitments[0].source") { value("MANUAL") }
                jsonPath("$.commitments[0].label") { value("Santa Casa") }
            }

        mockMvc.delete("/api/v1/availability/$entryId") { adminBearer() }.andExpect { status { isOk() } }
        mockMvc.delete("/api/v1/availability/$entryId") { adminBearer() }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `half a window is refused rather than stored as something uncomparable`() {
        mockMvc
            .post("/api/v1/availability") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"shiftDate":"2026-09-03","startTime":"07:00"}"""
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a shift claimed here becomes a commitment and stops a colliding offer`() {
        val first = ingestOffer(providerMessageId = "agenda-1")
        activateRuleSet(CONFLICT_REJECTS)
        reevaluate(first).andExpect { jsonPath("$.result") { value("ELIGIBLE") } }
        claim(first)

        // The same offer, posted again: same date, same hours, so it cannot also be hers.
        val second = ingestOffer(providerMessageId = "agenda-2")

        reevaluate(second).andExpect {
            status { isOk() }
            jsonPath("$.result") { value("REJECTED") }
            jsonPath("$.reasons[0]") { value("AGENDA_CONFLICT") }
        }
        mockMvc.get("/api/v1/availability") { adminBearer() }.andExpect {
            jsonPath("$.commitments[0].source") { value("CLAIM") }
        }
    }

    @Test
    fun `retracting the claim frees the date again`() {
        val first = ingestOffer(providerMessageId = "agenda-1")
        activateRuleSet(CONFLICT_REJECTS)
        reevaluate(first).andExpect { status { isOk() } }
        claim(first)
        processor.processDueEvents()
        val second = ingestOffer(providerMessageId = "agenda-2")
        reevaluate(second).andExpect { jsonPath("$.result") { value("REJECTED") } }

        val claimId = jdbcTemplate.queryForObject("select id from shift_claim", UUID::class.java).toString()
        mockMvc.post("/api/v1/claims/$claimId/retract") { adminBearer() }.andExpect { status { isOk() } }

        // Commitments are read from the claim, never mirrored into a table, so taking the claim back
        // takes the commitment back with it instead of leaving a ghost behind.
        reevaluate(second).andExpect {
            status { isOk() }
            jsonPath("$.result") { value("ELIGIBLE") }
        }
    }

    @Test
    fun `a hand-kept entry blocks an offer that crosses it`() {
        val opportunityId = ingestOffer()
        val shift = parsedShift()
        // Straddles the offer's start, whichever date the message resolved to.
        addEntry(shift.date, "15:00", "21:00")
        activateRuleSet(CONFLICT_REJECTS)

        reevaluate(opportunityId).andExpect {
            jsonPath("$.result") { value("REJECTED") }
            jsonPath("$.reasons[0]") { value("AGENDA_CONFLICT") }
        }
    }

    @Test
    fun `a second shift on the same day that never crosses stays hers to take`() {
        val opportunityId = ingestOffer()
        val shift = parsedShift()
        addEntry(shift.date, "07:00", "13:00")
        activateRuleSet(CONFLICT_REJECTS)

        reevaluate(opportunityId).andExpect { jsonPath("$.result") { value("ELIGIBLE") } }

        // The same diary, judged by date alone, is a collision.
        activateRuleSet("""{"agendaConflictPolicy":"REJECT","agendaConflictMode":"SAME_DAY"}""")
        reevaluate(opportunityId).andExpect { jsonPath("$.result") { value("REJECTED") } }
    }

    @Test
    fun `a rule set that says nothing about the agenda ignores it entirely`() {
        val opportunityId = ingestOffer()
        val shift = parsedShift()
        addEntry(shift.date, "15:00", "21:00")
        // The POC's own rule set shape. Its verdicts must not move because this rule now exists.
        activateRuleSet("{}")

        reevaluate(opportunityId).andExpect { jsonPath("$.result") { value("ELIGIBLE") } }
    }

    private data class ParsedShift(
        val date: LocalDate,
    )

    private fun parsedShift(): ParsedShift {
        val date =
            jdbcTemplate.queryForObject(
                "select shift_date from shift_opportunity order by detected_at limit 1",
                LocalDate::class.java,
            )
        assertNotNull(date, "the fixture offer must parse to a date for the agenda rule to have anything to read")
        return ParsedShift(date)
    }

    private fun addEntry(
        date: LocalDate,
        start: String?,
        end: String?,
        label: String? = null,
    ): String {
        val window = if (start == null) "" else ""","startTime":"$start","endTime":"$end""""
        val named = if (label == null) "" else ""","label":"$label""""
        val created =
            mockMvc
                .post("/api/v1/availability") {
                    adminBearer()
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"shiftDate":"$date"$window$named}"""
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString
        return JsonPath.read(created, "$.id")
    }

    private fun claim(opportunityId: String) {
        mockMvc
            .post("/api/v1/opportunities/$opportunityId/claim") { adminBearer() }
            .andExpect { status { isOk() } }
    }

    private fun reevaluate(opportunityId: String) = mockMvc.post("/api/v1/opportunities/$opportunityId/reevaluate") { adminBearer() }

    private fun activateRuleSet(definition: String) {
        val created =
            mockMvc
                .post("/api/v1/rule-sets") {
                    adminBearer()
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"agenda","definition":$definition}"""
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString
        val ruleSetId = JsonPath.read<String>(created, "$.id")
        mockMvc.post("/api/v1/rule-sets/$ruleSetId/activate") { adminBearer() }.andExpect { status { isOk() } }
    }

    private fun ingestOffer(providerMessageId: String = "agenda-1"): String {
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
                        "textMessageData": {"textMessage": "Plantao amanha 19-07 no PS Central R$ 1.200"}
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

    private fun MockHttpServletRequestDsl.adminBearer() {
        header("Authorization", "Bearer test-admin-token")
    }

    private companion object {
        const val GROUP_CHAT_ID = "120363000000000000@g.us"
        const val CONFLICT_REJECTS = """{"agendaConflictPolicy":"REJECT"}"""
    }
}
