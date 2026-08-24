package br.com.shiftcatcher.detection

import br.com.shiftcatcher.PostgresTestConfiguration
import br.com.shiftcatcher.ai.AiParseResult
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals

@Import(PostgresTestConfiguration::class, DetectionPipelineIntegrationTest.FakeAiConfiguration::class)
@SpringBootTest(
    webEnvironment = WebEnvironment.MOCK,
    properties = [
        "shift-catcher.security.admin-api-token=test-admin-token",
        "shift-catcher.green-api.instance-id=123456",
        "shift-catcher.green-api.webhook-token=test-webhook-token",
        "shift-catcher.detection.known-locations[0]=PS Central",
        "shift-catcher.detection.known-cities[0]=Bauru",
    ],
)
@AutoConfigureMockMvc
class DetectionPipelineIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val aiParser: FakeAiShiftParser,
) {
    @TestConfiguration
    class FakeAiConfiguration {
        @Bean
        @Primary
        fun fakeAiShiftParser(): FakeAiShiftParser = FakeAiShiftParser()
    }

    @BeforeEach
    fun reset() {
        jdbcTemplate.update("delete from shift_opportunity")
        jdbcTemplate.update("delete from detection_result")
        jdbcTemplate.update("delete from incoming_message")
        jdbcTemplate.update("delete from incoming_provider_event")
        jdbcTemplate.update("delete from allowed_group")
        aiParser.reset()
    }

    @Test
    fun `a complete offer becomes an opportunity waiting for the rule engine`() {
        registerGroup()

        postWebhook(webhook(text = "Plantao amanha 19-07 no PS Central R\$ 1.200")).andExpect {
            status { isOk() }
            jsonPath("$.processingStatus") { value("PROCESSED") }
            jsonPath("$.candidate") { value(true) }
            jsonPath("$.opportunityId") { exists() }
        }

        val row = jdbcTemplate.queryForMap("select * from shift_opportunity")
        assertEquals("EVALUATING", row["status"])
        assertEquals(LocalDate.of(2026, 8, 25), (row["shift_date"] as java.sql.Date).toLocalDate())
        assertEquals(LocalTime.of(19, 0), (row["start_time"] as java.sql.Time).toLocalTime())
        assertEquals(LocalTime.of(7, 0), (row["end_time"] as java.sql.Time).toLocalTime())
        assertEquals(true, row["ends_next_day"])
        assertEquals("PS Central", row["location"])
        assertEquals(0, (row["amount"] as BigDecimal).compareTo(BigDecimal("1200.00")))
        assertEquals("DETERMINISTIC", row["extraction_method"])
        assertEquals("", row["ambiguous_fields"])
        assertEquals(0, aiParser.calls.get(), "a resolved message must not reach the model")
    }

    @Test
    fun `ordinary conversation is processed without creating an opportunity`() {
        registerGroup()

        postWebhook(webhook(text = "bom dia pessoal, alguem viu meu estetoscopio?")).andExpect {
            status { isOk() }
            jsonPath("$.processingStatus") { value("PROCESSED") }
            jsonPath("$.candidate") { value(false) }
            jsonPath("$.opportunityId") { value(null) }
        }

        assertEquals(0, countOf("shift_opportunity"))
        assertEquals(false, jdbcTemplate.queryForObject("select candidate from detection_result", Boolean::class.java))
        assertEquals(0, aiParser.calls.get(), "DEC-004: irrelevant chatter never reaches the model")
    }

    @Test
    fun `an ambiguous offer waits for a human instead of guessing`() {
        registerGroup()

        postWebhook(webhook(text = "tem vaga de plantao alguem quer")).andExpect {
            status { isOk() }
            jsonPath("$.candidate") { value(true) }
        }

        val row = jdbcTemplate.queryForMap("select status, ambiguous_fields, resolution_reason from shift_opportunity")
        assertEquals("REVIEW_REQUIRED", row["status"])
        assertEquals("shiftDate,startTime,endTime", row["ambiguous_fields"])
        assertEquals("ESSENTIAL_FIELD_AMBIGUOUS", row["resolution_reason"])
    }

    @Test
    fun `the webhook request never calls the model even when it is enabled`() {
        registerGroup()
        aiParser.enabled = true
        aiParser.response = shiftOfferResult()

        postWebhook(webhook(text = "tem vaga de plantao alguem quer")).andExpect { status { isOk() } }

        assertEquals(0, aiParser.calls.get(), "Webhook-Contract forbids an AI call inside the request")
        assertEquals(
            "REVIEW_REQUIRED",
            jdbcTemplate.queryForObject("select status from shift_opportunity", String::class.java),
        )
    }

    @Test
    fun `reprocessing may use the model to resolve what the parser could not`() {
        registerGroup()
        postWebhook(webhook(text = "tem vaga de plantao alguem quer")).andExpect { status { isOk() } }
        aiParser.enabled = true
        aiParser.response = shiftOfferResult()

        reprocessLatestMessage().andExpect {
            status { isOk() }
            jsonPath("$.candidate") { value(true) }
        }

        assertEquals(1, aiParser.calls.get())
        val row = jdbcTemplate.queryForMap("select status, extraction_method, shift_date from shift_opportunity")
        assertEquals("EVALUATING", row["status"])
        assertEquals("AI_FALLBACK", row["extraction_method"])
        assertEquals(LocalDate.of(2026, 8, 25), (row["shift_date"] as java.sql.Date).toLocalDate())
    }

    @Test
    fun `an invalid model answer falls back to review`() {
        registerGroup()
        postWebhook(webhook(text = "tem vaga de plantao alguem quer")).andExpect { status { isOk() } }
        aiParser.enabled = true
        aiParser.response = shiftOfferResult().copy(confidence = BigDecimal("7.5"))

        reprocessLatestMessage().andExpect { status { isOk() } }

        val row = jdbcTemplate.queryForMap("select status, resolution_reason, extraction_method from shift_opportunity")
        assertEquals("REVIEW_REQUIRED", row["status"])
        assertEquals("AI_RESPONSE_INVALID", row["resolution_reason"])
        assertEquals("DETERMINISTIC", row["extraction_method"])
    }

    @Test
    fun `a model failure never breaks ingestion`() {
        registerGroup()
        postWebhook(webhook(text = "tem vaga de plantao alguem quer")).andExpect { status { isOk() } }
        aiParser.enabled = true
        aiParser.failure = IllegalStateException("provider down")

        reprocessLatestMessage().andExpect { status { isOk() } }

        val row = jdbcTemplate.queryForMap("select status, resolution_reason from shift_opportunity")
        assertEquals("REVIEW_REQUIRED", row["status"])
        assertEquals("AI_UNAVAILABLE", row["resolution_reason"])
    }

    @Test
    fun `the model may not terminate an opportunity on its own`() {
        registerGroup()
        postWebhook(webhook(text = "tem vaga de plantao alguem quer")).andExpect { status { isOk() } }
        aiParser.enabled = true
        aiParser.response = shiftOfferResult().copy(isShiftOffer = false)

        reprocessLatestMessage().andExpect { status { isOk() } }

        val row = jdbcTemplate.queryForMap("select status, resolution_reason from shift_opportunity")
        assertEquals("REVIEW_REQUIRED", row["status"], "AI interprets, it does not decide")
        assertEquals("AI_NOT_A_SHIFT_OFFER", row["resolution_reason"])
    }

    @Test
    fun `a message from a group that is not allowlisted is never parsed`() {
        postWebhook(webhook(text = "Plantao amanha 19-07 no PS Central R\$ 1.200")).andExpect {
            status { isOk() }
            jsonPath("$.processingStatus") { value("IGNORED") }
        }

        assertEquals(0, countOf("detection_result"))
        assertEquals(0, countOf("shift_opportunity"))
    }

    @Test
    fun `re-ingesting the same message keeps a single opportunity`() {
        registerGroup()
        val body = webhook(text = "Plantao amanha 19-07 no PS Central")

        postWebhook(body).andExpect { status { isOk() } }
        postWebhook(body).andExpect { jsonPath("$.status") { value("DUPLICATE") } }
        reprocessLatestMessage().andExpect { status { isOk() } }

        assertEquals(1, countOf("shift_opportunity"))
        assertEquals(1, countOf("detection_result"))
    }

    @Test
    fun `the sandbox explains a decision without storing anything`() {
        mockMvc
            .post("/api/v1/poc/detect") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"text":"Plantao amanha 19-07 no PS Central R$ 1.200",""" +
                    """"messageTimestamp":"2026-08-24T22:00:00Z"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.candidate") { value(true) }
                jsonPath("$.persisted") { value(false) }
                jsonPath("$.signals") { value(org.hamcrest.Matchers.hasItem("SHIFT_KEYWORD")) }
                jsonPath("$.extraction.shiftDate") { value("2026-08-25") }
                jsonPath("$.extraction.location") { value("PS Central") }
                jsonPath("$.ambiguousFields") { isEmpty() }
            }

        assertEquals(0, countOf("shift_opportunity"))
        assertEquals(0, countOf("detection_result"))
    }

    @Test
    fun `the sandbox requires text and a valid timestamp`() {
        postDetect("""{"messageTimestamp":"2026-08-24T22:00:00Z"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
        postDetect("""{"text":"plantao","messageTimestamp":"ontem"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `opportunity endpoints require the admin bearer token`() {
        mockMvc.get("/api/v1/opportunities").andExpect { status { isUnauthorized() } }
        mockMvc.post("/api/v1/poc/detect").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `manual review completes an opportunity and promotes it`() {
        registerGroup()
        postWebhook(webhook(text = "tem vaga de plantao alguem quer")).andExpect { status { isOk() } }
        val opportunity = latestOpportunity()

        mockMvc
            .post("/api/v1/opportunities/${opportunity.id}/review") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"shiftDate":"2026-08-25","startTime":"19:00","endTime":"07:00",""" +
                    """"location":"PS Central","reviewNote":"confirmado no grupo","version":${opportunity.version}}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("EVALUATING") }
                jsonPath("$.extractionMethod") { value("MANUAL_REVIEW") }
                jsonPath("$.endsNextDay") { value(true) }
                jsonPath("$.ambiguousFields") { isEmpty() }
                jsonPath("$.version") { value(opportunity.version + 1) }
            }
    }

    @Test
    fun `a half filled review keeps the opportunity in review`() {
        registerGroup()
        postWebhook(webhook(text = "tem vaga de plantao alguem quer")).andExpect { status { isOk() } }
        val opportunity = latestOpportunity()

        mockMvc
            .post("/api/v1/opportunities/${opportunity.id}/review") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"shiftDate":"2026-08-25","version":${opportunity.version}}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("REVIEW_REQUIRED") }
                jsonPath("$.ambiguousFields") { value(org.hamcrest.Matchers.hasItem("startTime")) }
            }
    }

    @Test
    fun `review refuses a stale or missing version`() {
        registerGroup()
        postWebhook(webhook(text = "tem vaga de plantao alguem quer")).andExpect { status { isOk() } }
        val opportunity = latestOpportunity()

        postReview(opportunity.id, """{"shiftDate":"2026-08-25","version":99}""").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STALE_VERSION") }
        }
        postReview(opportunity.id, """{"shiftDate":"2026-08-25"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `ignoring an opportunity rejects it and is idempotent`() {
        registerGroup()
        postWebhook(webhook(text = "tem vaga de plantao alguem quer")).andExpect { status { isOk() } }
        val opportunity = latestOpportunity()

        mockMvc.post("/api/v1/opportunities/${opportunity.id}/ignore") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("REJECTED") }
            jsonPath("$.resolutionReason") { value("MANUALLY_IGNORED") }
        }
        mockMvc.post("/api/v1/opportunities/${opportunity.id}/ignore") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("REJECTED") }
        }
    }

    @Test
    fun `a decided opportunity is never overwritten by a re-analysis`() {
        registerGroup()
        postWebhook(webhook(text = "tem vaga de plantao alguem quer")).andExpect { status { isOk() } }
        val opportunity = latestOpportunity()
        mockMvc.post("/api/v1/opportunities/${opportunity.id}/ignore") { adminBearer() }.andExpect { status { isOk() } }

        reprocessLatestMessage().andExpect { status { isOk() } }

        assertEquals(
            "REJECTED",
            jdbcTemplate.queryForObject("select status from shift_opportunity", String::class.java),
        )
    }

    @Test
    fun `listing and detail expose the parsed opportunity`() {
        registerGroup()
        postWebhook(webhook(text = "Plantao amanha 19-07 no PS Central R\$ 1.200")).andExpect { status { isOk() } }
        val opportunity = latestOpportunity()

        mockMvc.get("/api/v1/opportunities") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(1) }
            jsonPath("$.opportunities[0].status") { value("EVALUATING") }
        }
        mockMvc.get("/api/v1/opportunities/${opportunity.id}") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.location") { value("PS Central") }
            jsonPath("$.currency") { value("BRL") }
            jsonPath("$.sourceMessageId") { exists() }
        }
        mockMvc.get("/api/v1/opportunities/${UUID.randomUUID()}") { adminBearer() }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    private fun shiftOfferResult(): AiParseResult =
        AiParseResult(
            isShiftOffer = true,
            confidence = BigDecimal("0.92"),
            date = LocalDate.of(2026, 8, 25),
            startTime = LocalTime.of(19, 0),
            endTime = LocalTime.of(7, 0),
            durationHours = null,
            location = "PS Central",
            city = null,
            amount = null,
            currency = null,
            specialty = null,
            notes = null,
            ambiguousFields = emptyList(),
        )

    private data class OpportunitySnapshot(
        val id: String,
        val version: Int,
    )

    private fun latestOpportunity(): OpportunitySnapshot {
        val row = jdbcTemplate.queryForMap("select id, version from shift_opportunity order by detected_at desc limit 1")
        return OpportunitySnapshot(id = row["id"].toString(), version = row["version"] as Int)
    }

    private fun registerGroup() {
        mockMvc
            .post("/api/v1/groups") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"providerChatId":"$GROUP_CHAT_ID","displayName":"Plantoes"}"""
            }.andExpect { status { isOk() } }
    }

    private fun reprocessLatestMessage(): ResultActionsDsl {
        val messageId = jdbcTemplate.queryForObject("select id from incoming_message", UUID::class.java)
        return mockMvc.post("/api/v1/messages/$messageId/reprocess") { adminBearer() }
    }

    private fun postReview(
        opportunityId: String,
        body: String,
    ): ResultActionsDsl =
        mockMvc.post("/api/v1/opportunities/$opportunityId/review") {
            adminBearer()
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun postDetect(body: String): ResultActionsDsl =
        mockMvc.post("/api/v1/poc/detect") {
            adminBearer()
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun postWebhook(body: String): ResultActionsDsl =
        mockMvc.post("/api/v1/webhooks/green-api") {
            header("Authorization", "Bearer test-webhook-token")
            header("X-Correlation-Id", "detection-test")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun MockHttpServletRequestDsl.adminBearer() {
        header("Authorization", "Bearer test-admin-token")
    }

    private fun countOf(table: String): Int = jdbcTemplate.queryForObject("select count(*) from $table", Int::class.java) ?: 0

    /** 2026-08-24T22:00:00Z is 19:00 on the 24th in Sao Paulo, so "amanha" is the 25th. */
    private fun webhook(
        text: String,
        providerMessageId: String = "detection-message-1",
    ): String =
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

    private companion object {
        const val GROUP_CHAT_ID = "120363000000000000@g.us"
    }
}
