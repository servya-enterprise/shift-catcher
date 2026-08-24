package br.com.shiftcatcher.messaging

import br.com.shiftcatcher.PostgresTestConfiguration
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
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
import kotlin.test.assertNull

@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    webEnvironment = WebEnvironment.MOCK,
    properties = [
        "shift-catcher.security.admin-api-token=test-admin-token",
        "shift-catcher.green-api.instance-id=123456",
        "shift-catcher.green-api.webhook-token=test-webhook-token",
    ],
)
@AutoConfigureMockMvc
class IngestionAndAllowlistIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun reset() {
        jdbcTemplate.update("delete from shift_opportunity")
        jdbcTemplate.update("delete from detection_result")
        jdbcTemplate.update("delete from incoming_message")
        jdbcTemplate.update("delete from incoming_provider_event")
        jdbcTemplate.update("delete from allowed_group")
    }

    @Test
    fun `allowlist endpoints require the admin bearer token`() {
        mockMvc.get("/api/v1/groups").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
        mockMvc.get("/api/v1/messages").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a registered group starts enabled with auto claim off`() {
        val group = registerGroup(GROUP_CHAT_ID, "Plantões")

        assertEquals(true, JsonPath.read(group, "$.enabled"))
        assertEquals(false, JsonPath.read<Boolean>(group, "$.autoClaimEnabled"))
        assertEquals(0, JsonPath.read<Int>(group, "$.version"))

        mockMvc.get("/api/v1/groups") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(1) }
            jsonPath("$.groups[0].providerChatId") { value(GROUP_CHAT_ID) }
        }
        mockMvc.get("/api/v1/groups/${groupId(group)}") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.displayName") { value("Plantões") }
        }
    }

    @Test
    fun `the same provider chat cannot be registered twice`() {
        registerGroup(GROUP_CHAT_ID)

        postGroup("""{"providerChatId":"$GROUP_CHAT_ID"}""").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONFLICT") }
        }
        assertEquals(1, countOf("allowed_group"))
    }

    @Test
    fun `only group chats can be allowlisted`() {
        postGroup("""{"providerChatId":"5511999999999@c.us"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
        assertEquals(0, countOf("allowed_group"))
    }

    @Test
    fun `an unknown group is a not found problem`() {
        mockMvc.get("/api/v1/groups/${UUID.randomUUID()}") { adminBearer() }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    @Test
    fun `editing a group requires the current version`() {
        val id = groupId(registerGroup(GROUP_CHAT_ID))

        mockMvc
            .patch("/api/v1/groups/$id") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"displayName":"Renomeado","version":7}"""
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("STALE_VERSION") }
            }

        mockMvc
            .patch("/api/v1/groups/$id") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"displayName":"Renomeado","version":0}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.displayName") { value("Renomeado") }
                jsonPath("$.version") { value(1) }
            }
    }

    @Test
    fun `repeating a toggle does not consume a version`() {
        val id = groupId(registerGroup(GROUP_CHAT_ID))

        mockMvc.post("/api/v1/groups/$id/disable") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.enabled") { value(false) }
            jsonPath("$.version") { value(1) }
        }
        mockMvc.post("/api/v1/groups/$id/disable") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.enabled") { value(false) }
            jsonPath("$.version") { value(1) }
        }
        mockMvc.post("/api/v1/groups/$id/enable") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.enabled") { value(true) }
            jsonPath("$.version") { value(2) }
        }
    }

    @Test
    fun `auto claim is only ever enabled explicitly`() {
        val id = groupId(registerGroup(GROUP_CHAT_ID))

        mockMvc.post("/api/v1/groups/$id/auto-claim/enable") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.autoClaimEnabled") { value(true) }
            jsonPath("$.enabled") { value(true) }
        }
        mockMvc.post("/api/v1/groups/$id/auto-claim/disable") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.autoClaimEnabled") { value(false) }
        }
    }

    @Test
    fun `a message from an unregistered group is stored but not queued`() {
        postWebhook(webhook()).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACCEPTED") }
            jsonPath("$.processingStatus") { value("IGNORED") }
            jsonPath("$.ignoredReason") { value("GROUP_NOT_ALLOWLISTED") }
            jsonPath("$.messageId") { exists() }
        }

        val stored = jdbcTemplate.queryForMap("select group_id, text from incoming_message")
        assertNull(stored["group_id"])
        assertEquals("vaga de amanha as 8h", stored["text"])
        assertEquals("IGNORED", processingStatus())
    }

    @Test
    fun `a message from an allowlisted group is analyzed on arrival`() {
        val id = groupId(registerGroup(GROUP_CHAT_ID))

        postWebhook(webhook()).andExpect {
            status { isOk() }
            jsonPath("$.processingStatus") { value("PROCESSED") }
            jsonPath("$.ignoredReason") { value(null) }
        }

        val storedGroup = jdbcTemplate.queryForObject("select group_id from incoming_message", UUID::class.java)
        assertEquals(id, storedGroup.toString())
        assertEquals("PROCESSED", processingStatus())
    }

    @Test
    fun `a disabled group keeps its own ignore reason`() {
        val id = groupId(registerGroup(GROUP_CHAT_ID))
        mockMvc.post("/api/v1/groups/$id/disable") { adminBearer() }.andExpect { status { isOk() } }

        postWebhook(webhook()).andExpect {
            status { isOk() }
            jsonPath("$.processingStatus") { value("IGNORED") }
            jsonPath("$.ignoredReason") { value("GROUP_DISABLED") }
        }
    }

    @Test
    fun `direct chats and non text messages are acknowledged without being stored`() {
        registerGroup(GROUP_CHAT_ID)

        postWebhook(webhook(chatId = "5511999999999@c.us")).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("IGNORED") }
        }
        postWebhook(webhook(typeMessage = "imageMessage")).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("IGNORED") }
        }

        assertEquals(0, countOf("incoming_provider_event"))
        assertEquals(0, countOf("incoming_message"))
    }

    @Test
    fun `a redelivered webhook does not duplicate the message log`() {
        registerGroup(GROUP_CHAT_ID)

        postWebhook(webhook()).andExpect { jsonPath("$.status") { value("ACCEPTED") } }
        postWebhook(webhook()).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DUPLICATE") }
            jsonPath("$.processingStatus") { value("PROCESSED") }
        }

        assertEquals(1, countOf("incoming_message"))
        assertEquals(
            1,
            jdbcTemplate.queryForObject("select duplicate_count from incoming_provider_event", Int::class.java),
        )
    }

    @Test
    fun `normalization collapses whitespace while the raw payload stays intact`() {
        postWebhook(webhook(text = "vaga   de\\namanha \\t as 8h  ")).andExpect { status { isOk() } }

        assertEquals(
            "vaga de amanha as 8h",
            jdbcTemplate.queryForObject("select text from incoming_message", String::class.java),
        )
        assertEquals(
            "vaga   de\namanha \t as 8h  ",
            jdbcTemplate.queryForObject("select message_text from incoming_provider_event", String::class.java),
        )
    }

    @Test
    fun `the payload hash and correlation id are recorded with the event`() {
        postWebhook(webhook()).andExpect { status { isOk() } }

        val row = jdbcTemplate.queryForMap("select payload_hash, correlation_id from incoming_provider_event")
        assertEquals(64, (row["payload_hash"] as String).length)
        assertEquals("ingestion-test", row["correlation_id"])
    }

    @Test
    fun `registering a group afterwards promotes its stored messages on reprocess`() {
        postWebhook(webhook()).andExpect { jsonPath("$.processingStatus") { value("IGNORED") } }
        val messageId = jdbcTemplate.queryForObject("select id from incoming_message", UUID::class.java).toString()
        val id = groupId(registerGroup(GROUP_CHAT_ID))

        mockMvc.post("/api/v1/messages/$messageId/reprocess") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.processingStatus") { value("PROCESSED") }
            jsonPath("$.groupId") { value(id) }
            jsonPath("$.changed") { value(true) }
        }
        mockMvc.post("/api/v1/messages/$messageId/reprocess") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.processingStatus") { value("PROCESSED") }
            jsonPath("$.changed") { value(false) }
        }

        assertEquals(1, countOf("incoming_message"))
        assertEquals("PROCESSED", processingStatus())
    }

    @Test
    fun `reprocessing an unknown message is a not found problem`() {
        mockMvc.post("/api/v1/messages/${UUID.randomUUID()}/reprocess") { adminBearer() }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    @Test
    fun `the message log exposes the ingestion decision`() {
        postWebhook(webhook()).andExpect { status { isOk() } }
        val messageId = jdbcTemplate.queryForObject("select id from incoming_message", UUID::class.java).toString()

        mockMvc.get("/api/v1/messages") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(1) }
            jsonPath("$.messages[0].chatId") { value(GROUP_CHAT_ID) }
            jsonPath("$.messages[0].processingStatus") { value("IGNORED") }
        }
        mockMvc.get("/api/v1/messages/$messageId") { adminBearer() }.andExpect {
            status { isOk() }
            jsonPath("$.providerMessageId") { value("incoming-message-1") }
            jsonPath("$.senderId") { value("5511999999999@c.us") }
            jsonPath("$.ignoredReason") { value("GROUP_NOT_ALLOWLISTED") }
            jsonPath("$.eventId") { exists() }
        }
    }

    private fun registerGroup(
        chatId: String,
        displayName: String? = null,
    ): String {
        val body =
            if (displayName == null) {
                """{"providerChatId":"$chatId"}"""
            } else {
                """{"providerChatId":"$chatId","displayName":"$displayName"}"""
            }
        return postGroup(body)
            .andExpect { status { isOk() } }
            .andReturn()
            .response
            .contentAsString
    }

    private fun postGroup(body: String): ResultActionsDsl =
        mockMvc.post("/api/v1/groups") {
            adminBearer()
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun postWebhook(body: String): ResultActionsDsl =
        mockMvc.post("/api/v1/webhooks/green-api") {
            header("Authorization", "Bearer test-webhook-token")
            header("X-Correlation-Id", "ingestion-test")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun MockHttpServletRequestDsl.adminBearer() {
        header("Authorization", "Bearer test-admin-token")
    }

    private fun groupId(groupJson: String): String = JsonPath.read(groupJson, "$.id")

    private fun countOf(table: String): Int = jdbcTemplate.queryForObject("select count(*) from $table", Int::class.java) ?: 0

    private fun processingStatus(): String? =
        jdbcTemplate.queryForObject("select processing_status from incoming_provider_event", String::class.java)

    private fun webhook(
        chatId: String = GROUP_CHAT_ID,
        typeMessage: String = "textMessage",
        text: String = "vaga de amanha as 8h",
        providerMessageId: String = "incoming-message-1",
    ): String =
        """
        {
          "typeWebhook": "incomingMessageReceived",
          "instanceData": {"idInstance": 123456},
          "timestamp": 1787227200,
          "idMessage": "$providerMessageId",
          "senderData": {
            "chatId": "$chatId",
            "chatName": "Plantões",
            "sender": "5511999999999@c.us",
            "senderName": "Pessoa"
          },
          "messageData": {
            "typeMessage": "$typeMessage",
            "textMessageData": {"textMessage": "$text"}
          }
        }
        """.trimIndent()

    private companion object {
        const val GROUP_CHAT_ID = "120363000000000000@g.us"
    }
}
