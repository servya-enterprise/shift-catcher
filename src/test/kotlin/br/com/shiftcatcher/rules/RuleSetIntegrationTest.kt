package br.com.shiftcatcher.rules

import br.com.shiftcatcher.PostgresTestConfiguration
import br.com.shiftcatcher.integration.greenapi.GreenApiInstanceState
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

@Import(PostgresTestConfiguration::class, RuleSetIntegrationTest.FakeProviderConfiguration::class)
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
class RuleSetIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val instanceHealth: FakeInstanceHealth,
) {
    @TestConfiguration
    class FakeProviderConfiguration {
        @Bean
        @Primary
        fun fakeInstanceHealth(): FakeInstanceHealth = FakeInstanceHealth()
    }

    @BeforeEach
    fun reset() {
        instanceHealth.reset()
        jdbcTemplate.update("delete from rule_evaluation")
        jdbcTemplate.update("delete from rule_set")
        jdbcTemplate.update("delete from shift_opportunity")
        jdbcTemplate.update("delete from detection_result")
        jdbcTemplate.update("delete from incoming_message")
        jdbcTemplate.update("delete from incoming_provider_event")
        jdbcTemplate.update("delete from allowed_group")
    }

    @Test
    fun `rule set endpoints require the admin bearer token`() {
        mockMvc.get("/api/v1/rule-sets").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a new rule set starts as a numbered draft`() {
        val created = createRuleSet("""{"name":"conservador","definition":{"minAmount":800}}""")

        assertEquals(1, JsonPath.read<Int>(created, "$.version"))
        assertEquals("DRAFT", JsonPath.read<String>(created, "$.status"))
        assertEquals(800, JsonPath.read<Int>(created, "$.definition.minAmount"))

        mockMvc.get("/api/v1/rule-sets") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(1) }
        }
        mockMvc.get("/api/v1/rule-sets/${idOf(created)}") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("conservador") }
        }
    }

    @Test
    fun `an invalid definition is refused before it can ever be activated`() {
        postRuleSet("""{"definition":{"minConfidence":7.5}}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
        postRuleSet("""{"definition":{"requiredFields":["naoExiste"]}}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
        postRuleSet("""{"definition":{"maxDurationHours":48}}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
        assertEquals(0, countOf("rule_set"))
    }

    @Test
    fun `a draft can be edited but an active version is immutable`() {
        val id = idOf(createRuleSet("""{"name":"v1","definition":{"minAmount":800}}"""))

        mockMvc
            .patch("/api/v1/rule-sets/$id") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"v1 revisado","definition":{"minAmount":900}}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.definition.minAmount") { value(900) }
            }

        mockMvc.post("/api/v1/rule-sets/$id/activate") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.activatedAt") { exists() }
        }

        mockMvc
            .patch("/api/v1/rule-sets/$id") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"definition":{"minAmount":100}}"""
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("CONFLICT") }
            }
    }

    @Test
    fun `activating a new version supersedes the previous one`() {
        val first = idOf(createRuleSet("""{"name":"v1","definition":{}}"""))
        activate(first)
        val second = idOf(createRuleSet("""{"name":"v2","definition":{}}"""))
        activate(second)

        assertEquals(
            1,
            jdbcTemplate.queryForObject("select count(*) from rule_set where status = 'ACTIVE'", Int::class.java),
        )
        assertEquals(
            "SUPERSEDED",
            jdbcTemplate.queryForObject("select status from rule_set where id = ?::uuid", String::class.java, first),
        )
        mockMvc.post("/api/v1/rule-sets/$first/activate") { adminBearer() }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONFLICT") }
        }
    }

    @Test
    fun `activating the already active version is a no-op`() {
        val id = idOf(createRuleSet("""{"definition":{}}"""))
        activate(id)

        mockMvc.post("/api/v1/rule-sets/$id/activate") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.version") { value(1) }
        }
    }

    @Test
    fun `without an active rule set nothing becomes eligible`() {
        val opportunityId = anOpportunity()

        reevaluate(opportunityId).andExpect {
            status { isOk() }
            jsonPath("$.result") { value("REVIEW_REQUIRED") }
            jsonPath("$.reasons") { value(org.hamcrest.Matchers.hasItem("NO_ACTIVE_RULE_SET")) }
            jsonPath("$.status") { value("REVIEW_REQUIRED") }
            jsonPath("$.autoClaimAllowed") { value(false) }
        }
    }

    @Test
    fun `a permissive rule set promotes the opportunity to eligible`() {
        val opportunityId = anOpportunity()
        activate(idOf(createRuleSet("""{"name":"tudo","definition":{}}""")))

        reevaluate(opportunityId).andExpect {
            status { isOk() }
            jsonPath("$.result") { value("ELIGIBLE") }
            jsonPath("$.status") { value("ELIGIBLE") }
            jsonPath("$.ruleSetVersion") { value(1) }
            jsonPath("$.autoClaimAllowed") { value(false) }
        }

        assertEquals(
            "ELIGIBLE",
            jdbcTemplate.queryForObject("select status from shift_opportunity", String::class.java),
        )
    }

    @Test
    fun `a hard rule the offer misses rejects it`() {
        val opportunityId = anOpportunity()
        activate(idOf(createRuleSet("""{"definition":{"minAmount":5000}}""")))

        reevaluate(opportunityId).andExpect {
            status { isOk() }
            jsonPath("$.result") { value("REJECTED") }
            jsonPath("$.status") { value("REJECTED") }
            jsonPath("$.reasons") { value(org.hamcrest.Matchers.hasItem("AMOUNT_BELOW_MINIMUM")) }
        }
    }

    @Test
    fun `an unreachable provider keeps the offer in review`() {
        val opportunityId = anOpportunity()
        // GREEN-API has no api-url in this context, so the state call fails and stays unknown.
        activate(idOf(createRuleSet("""{"definition":{"requireOperationalInstance":true}}""")))

        reevaluate(opportunityId).andExpect {
            status { isOk() }
            jsonPath("$.result") { value("REVIEW_REQUIRED") }
            jsonPath("$.reasons") { value(org.hamcrest.Matchers.hasItem("INSTANCE_STATE_UNKNOWN")) }
        }
    }

    @Test
    fun `the evaluation records the rule set version that produced it`() {
        val opportunityId = anOpportunity()
        val first = idOf(createRuleSet("""{"definition":{}}"""))
        activate(first)
        reevaluate(opportunityId).andExpect { status { isOk() } }

        activate(idOf(createRuleSet("""{"definition":{"minAmount":5000}}""")))

        val row = jdbcTemplate.queryForMap("select rule_set_version, result from rule_evaluation")
        assertEquals(1, row["rule_set_version"], "a past verdict keeps pointing at the version that made it")
        assertEquals("ELIGIBLE", row["result"])
    }

    @Test
    fun `a manually ignored opportunity is not resurrected by a re-evaluation`() {
        val opportunityId = anOpportunity()
        mockMvc.post("/api/v1/opportunities/$opportunityId/ignore") { adminBearer() }.andExpect { status { isOk() } }
        activate(idOf(createRuleSet("""{"definition":{}}""")))

        reevaluate(opportunityId).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONFLICT") }
        }
    }

    @Test
    fun `re-evaluating an unknown opportunity is a not found problem`() {
        reevaluate(UUID.randomUUID().toString()).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    @Test
    fun `a simulation answers what a draft would do without moving anything`() {
        val opportunityId = anOpportunity()
        val draft = idOf(createRuleSet("""{"name":"rascunho","definition":{"minAmount":5000}}"""))

        mockMvc.post("/api/v1/rule-sets/$draft/simulate") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.evaluated") { value(1) }
            jsonPath("$.rejected") { value(1) }
            jsonPath("$.results[0].simulated") { value(true) }
            jsonPath("$.results[0].result") { value("REJECTED") }
            jsonPath("$.results[0].status") { value("EVALUATING") }
        }

        assertEquals(0, countOf("rule_evaluation"), "a simulation persists no verdict")
        assertEquals(
            "EVALUATING",
            jdbcTemplate.queryForObject("select status from shift_opportunity", String::class.java),
            "a simulation moves no opportunity",
        )
        assertEquals(opportunityId, jdbcTemplate.queryForObject("select id from shift_opportunity", UUID::class.java).toString())
    }

    @Test
    fun `the provider state is asked once for a whole simulation`() {
        instanceHealth.state = GreenApiInstanceState.AUTHORIZED
        anOpportunity("batch-1")
        anOpportunity("batch-2")
        anOpportunity("batch-3")
        val draft = idOf(createRuleSet("""{"definition":{"requireOperationalInstance":true}}"""))

        mockMvc.post("/api/v1/rule-sets/$draft/simulate") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.evaluated") { value(3) }
            jsonPath("$.eligible") { value(3) }
        }

        // The provider rate-limits getStateInstance: asking per opportunity burns the quota and
        // makes rows of the same simulation disagree with each other.
        assertEquals(1, instanceHealth.calls.get())
    }

    @Test
    fun `a rule set that needs no provider state never calls the provider`() {
        instanceHealth.state = GreenApiInstanceState.AUTHORIZED
        val opportunityId = anOpportunity()
        activate(idOf(createRuleSet("""{"definition":{}}""")))

        reevaluate(opportunityId).andExpect { status { isOk() } }

        assertEquals(0, instanceHealth.calls.get())
    }

    @Test
    fun `a simulation can target specific opportunities`() {
        val opportunityId = anOpportunity()
        val draft = idOf(createRuleSet("""{"definition":{}}"""))

        mockMvc
            .post("/api/v1/rule-sets/$draft/simulate") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"opportunityIds":["$opportunityId"]}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.evaluated") { value(1) }
                jsonPath("$.eligible") { value(1) }
            }
    }

    /** Registers the group and ingests a complete offer, leaving one opportunity in EVALUATING. */
    private fun anOpportunity(providerMessageId: String = "rules-message-1"): String {
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
                        "textMessageData": {"textMessage": "Plantao amanha 19-07 no PS Central em Bauru R$ 1.200"}
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

    private fun createRuleSet(body: String): String =
        postRuleSet(body)
            .andExpect { status { isOk() } }
            .andReturn()
            .response.contentAsString

    private fun postRuleSet(body: String): ResultActionsDsl =
        mockMvc.post("/api/v1/rule-sets") {
            adminBearer()
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun activate(ruleSetId: String) {
        mockMvc.post("/api/v1/rule-sets/$ruleSetId/activate") { adminBearer() }.andExpect { status { isOk() } }
    }

    private fun reevaluate(opportunityId: String): ResultActionsDsl =
        mockMvc.post("/api/v1/opportunities/$opportunityId/reevaluate") { adminBearer() }

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    private fun countOf(table: String): Int = jdbcTemplate.queryForObject("select count(*) from $table", Int::class.java) ?: 0

    private fun MockHttpServletRequestDsl.adminBearer() {
        header("Authorization", "Bearer test-admin-token")
    }

    private companion object {
        const val GROUP_CHAT_ID = "120363000000000000@g.us"
    }
}
