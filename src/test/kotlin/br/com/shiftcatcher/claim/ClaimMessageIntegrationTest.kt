package br.com.shiftcatcher.claim

import br.com.shiftcatcher.PostgresTestConfiguration
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The reply stops being a constant (`12-MVP/MVP-Scope.md`).
 *
 * `PEGO` remains the default, so an installation that never touches the setting behaves exactly as
 * the frozen POC did. What is sent is resolved once, when the claim is decided, and frozen there
 * next to the quote target.
 */
@Import(PostgresTestConfiguration::class, ClaimMessageIntegrationTest.FakeProviderConfiguration::class)
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
class ClaimMessageIntegrationTest(
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
        jdbcTemplate.update("delete from provider_health")
        jdbcTemplate.update("update claim_message_setting set message = 'PEGO', version = 0")
        sender.reset()
    }

    @Test
    fun `the setting requires the admin bearer token`() {
        mockMvc.get("/api/v1/settings/claim-message").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the default wording is the one the POC froze`() {
        mockMvc.get("/api/v1/settings/claim-message") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.message") { value("PEGO") }
        }
    }

    @Test
    fun `changing the wording changes what is actually sent`() {
        setGlobalMessage("Tenho interesse!")
        val opportunityId = anEligibleOpportunity()

        mockMvc.post("/api/v1/opportunities/$opportunityId/claim") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.message") { value("Tenho interesse!") }
        }
        processor.processDueEvents()

        assertEquals("Tenho interesse!", sender.sends.single().message)
    }

    @Test
    fun `a group speaks in its own words when it has any`() {
        setGlobalMessage("Tenho interesse!")
        val opportunityId = anEligibleOpportunity()
        val groupId = jdbcTemplate.queryForObject("select id from allowed_group", UUID::class.java).toString()
        val version = jdbcTemplate.queryForObject("select version from allowed_group", Int::class.java)
        mockMvc
            .patch("/api/v1/groups/$groupId") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"claimMessage":"PEGO esse","version":$version}"""
            }.andExpect { status { isOk() } }

        mockMvc.post("/api/v1/opportunities/$opportunityId/claim") { adminBearer() }.andExpect {
            jsonPath("$.message") { value("PEGO esse") }
        }
    }

    @Test
    fun `what was sent stays what it was after the wording changes`() {
        val opportunityId = anEligibleOpportunity()
        mockMvc
            .post("/api/v1/opportunities/$opportunityId/claim") { adminBearer() }
            .andExpect { status { isOk() } }
        processor.processDueEvents()

        setGlobalMessage("Outra coisa")

        // The claim is the record of a message that really exists in a group. Rewriting it to match
        // a setting changed afterwards would make the record a lie.
        assertEquals(
            "PEGO",
            jdbcTemplate.queryForObject("select message from shift_claim", String::class.java),
        )
        assertEquals("PEGO", sender.sends.single().message)
    }

    @Test
    fun `a wording that says nothing is refused`() {
        listOf("""{"message":"   "}""", """{"message":"linha\numa"}""", "{}").forEach { body ->
            mockMvc
                .put("/api/v1/settings/claim-message") {
                    adminBearer()
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isBadRequest() } }
        }
        mockMvc.get("/api/v1/settings/claim-message") { adminBearer() }.andExpect {
            jsonPath("$.message") { value("PEGO") }
        }
    }

    @Test
    fun `a stale version is refused rather than silently overwriting`() {
        setGlobalMessage("Primeira")

        mockMvc
            .put("/api/v1/settings/claim-message") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"message":"Segunda","version":0}"""
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("STALE_VERSION") }
            }
    }

    private fun setGlobalMessage(message: String) {
        mockMvc
            .put("/api/v1/settings/claim-message") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"message":"$message"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.message") { value(message) }
            }
    }

    private fun anEligibleOpportunity(): String {
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
                      "idMessage": "wording-1",
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

        val opportunityId =
            jdbcTemplate.queryForObject("select id from shift_opportunity", UUID::class.java).toString()
        mockMvc.post("/api/v1/opportunities/$opportunityId/reevaluate") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.result") { value("ELIGIBLE") }
        }
        return opportunityId
    }

    private fun MockHttpServletRequestDsl.adminBearer() {
        header("Authorization", "Bearer test-admin-token")
    }

    private companion object {
        const val GROUP_CHAT_ID = "120363000000000000@g.us"
    }
}
