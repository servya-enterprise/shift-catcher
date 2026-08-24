package br.com.shiftcatcher.reliability

import br.com.shiftcatcher.PostgresTestConfiguration
import br.com.shiftcatcher.claim.FakeMessageSender
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
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The safe default deserves its own context: with `auto-claim-enabled` left unset, an opportunity
 * that every other switch approves must still never be claimed on its own.
 */
@Import(PostgresTestConfiguration::class, AutoClaimDefaultOffIntegrationTest.FakeProviderConfiguration::class)
@SpringBootTest(
    webEnvironment = WebEnvironment.MOCK,
    properties = [
        "shift-catcher.security.admin-api-token=test-admin-token",
        "shift-catcher.green-api.instance-id=123456",
        "shift-catcher.green-api.webhook-token=test-webhook-token",
        "shift-catcher.claim.worker-enabled=false",
    ],
)
@AutoConfigureMockMvc
class AutoClaimDefaultOffIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val sender: FakeMessageSender,
    @Autowired private val healthGate: ProviderHealthGate,
    @Autowired private val autoClaimTrigger: AutoClaimTrigger,
) {
    @TestConfiguration
    class FakeProviderConfiguration {
        @Bean
        @Primary
        fun fakeMessageSender(): FakeMessageSender = FakeMessageSender()

        @Bean
        @Primary
        fun alwaysOperationalHealth(): ReliabilityIntegrationTest.ToggleableInstanceHealth =
            ReliabilityIntegrationTest.ToggleableInstanceHealth()
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
    }

    @Test
    fun `nothing is claimed automatically while the application flag is unset`() {
        ingestOffer()
        val groupId = jdbcTemplate.queryForObject("select id from allowed_group", UUID::class.java).toString()
        mockMvc.post("/api/v1/groups/$groupId/auto-claim/enable") { adminBearer() }.andExpect { status { isOk() } }
        activateAutoRuleSet()
        val opportunityId = jdbcTemplate.queryForObject("select id from shift_opportunity", UUID::class.java).toString()
        mockMvc.post("/api/v1/opportunities/$opportunityId/reevaluate") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.autoClaimAllowed") { value(true) }
        }
        healthGate.refresh()

        val summary = autoClaimTrigger.runOnce()

        assertEquals("AUTO_CLAIM_DISABLED", summary.skippedReason)
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from shift_claim", Int::class.java))
        assertEquals(0, sender.calls.get())
    }

    private fun activateAutoRuleSet() {
        val created =
            mockMvc
                .post("/api/v1/rule-sets") {
                    adminBearer()
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"auto","definition":{"autoClaimEnabled":true}}"""
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString
        val ruleSetId = JsonPath.read<String>(created, "$.id")
        mockMvc.post("/api/v1/rule-sets/$ruleSetId/activate") { adminBearer() }.andExpect { status { isOk() } }
    }

    private fun ingestOffer() {
        mockMvc.post("/api/v1/groups") {
            adminBearer()
            contentType = MediaType.APPLICATION_JSON
            content = """{"providerChatId":"120363000000000000@g.us","displayName":"Plantoes"}"""
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
                      "idMessage": "default-off-1",
                      "senderData": {
                        "chatId": "120363000000000000@g.us",
                        "chatName": "Plantoes",
                        "sender": "5511999999999@c.us",
                        "senderName": "Pessoa"
                      },
                      "messageData": {
                        "typeMessage": "textMessage",
                        "textMessageData": {"textMessage": "Plantao amanha 19-07 R\$ 1.200"}
                      }
                    }
                    """.trimIndent()
            }.andExpect { status { isOk() } }
    }

    private fun MockHttpServletRequestDsl.adminBearer() {
        header("Authorization", "Bearer test-admin-token")
    }
}
