package br.com.shiftcatcher.integration.greenapi

import br.com.shiftcatcher.PostgresTestConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    webEnvironment = WebEnvironment.MOCK,
    properties = [
        "shift-catcher.security.admin-api-token=test-admin-token",
        "shift-catcher.green-api.instance-id=123456",
        "shift-catcher.green-api.api-token=test-api-token-123",
        "shift-catcher.green-api.webhook-token=test-webhook-token",
        "shift-catcher.green-api.allow-insecure-http=true",
        "shift-catcher.green-api.connect-timeout=1s",
        "shift-catcher.green-api.read-timeout=1s",
    ],
)
@AutoConfigureMockMvc
class GreenApiTransportIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val service: GreenApiTransportService,
) {
    @BeforeEach
    fun reset() {
        jdbcTemplate.update("delete from transport_test_reply")
        jdbcTemplate.update("delete from shift_opportunity")
        jdbcTemplate.update("delete from detection_result")
        jdbcTemplate.update("delete from incoming_message")
        jdbcTemplate.update("delete from incoming_provider_event")
        jdbcTemplate.update("delete from allowed_group")
        fake.reset()
    }

    @Test
    fun `webhook requires its separate bearer token`() {
        mockMvc
            .post("/api/v1/webhooks/green-api") {
                contentType = MediaType.APPLICATION_JSON
                content = incomingWebhook()
            }.andExpect {
                status { isUnauthorized() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.code") { value("WEBHOOK_UNAUTHORIZED") }
            }
    }

    @Test
    fun `webhook rejects an oversized payload before parsing`() {
        postWebhook(" ".repeat(256 * 1024 + 1)).andExpect {
            status { isContentTooLarge() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }

        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from incoming_provider_event", Int::class.java))
    }

    @Test
    fun `webhook persists exact transport identifiers and deduplicates retry`() {
        postWebhook(incomingWebhook()).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACCEPTED") }
            jsonPath("$.eventId") { exists() }
            jsonPath("$.persistedAt") { exists() }
        }
        postWebhook(incomingWebhook()).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DUPLICATE") }
        }

        val row =
            jdbcTemplate.queryForMap(
                """
                select instance_id, provider_message_id, chat_id, sender_id, message_text, duplicate_count
                  from incoming_provider_event
                """.trimIndent(),
            )
        assertEquals("123456", row["instance_id"])
        assertEquals("incoming-message-1", row["provider_message_id"])
        assertEquals("120363000000000000@g.us", row["chat_id"])
        assertEquals("5511999999999@c.us", row["sender_id"])
        assertEquals("teste shift catcher", row["message_text"])
        assertEquals(1, row["duplicate_count"])
        assertEquals(0, fake.stateCalls.get())
        assertEquals(0, fake.sendCalls.get())
    }

    @Test
    fun `unsupported webhook is acknowledged without entering the pipeline`() {
        postWebhook("""{"typeWebhook":"outgoingMessageStatus"}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("IGNORED") }
        }

        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from incoming_provider_event", Int::class.java))
        assertEquals(0, fake.stateCalls.get())
        assertEquals(0, fake.sendCalls.get())
    }

    @Test
    fun `malformed incoming webhook fails safe as Problem Details`() {
        postWebhook("""{"typeWebhook":"incomingMessageReceived"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
            jsonPath("$.correlationId") { exists() }
        }
    }

    @Test
    fun `state endpoint normalizes operational state`() {
        mockMvc
            .get("/api/v1/integrations/green-api/state") {
                adminBearer()
            }.andExpect {
                status { isOk() }
                jsonPath("$.configured") { value(true) }
                jsonPath("$.state") { value("AUTHORIZED") }
                jsonPath("$.rawState") { value("authorized") }
                jsonPath("$.operational") { value(true) }
            }
    }

    @Test
    fun `non operational state blocks send before provider effect`() {
        fake.respondToState(body = """{"stateInstance":"notAuthorized"}""")

        postTestReply("blocked-send-key", "incoming-message-1").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("INSTANCE_NOT_OPERATIONAL") }
        }

        assertEquals(0, fake.sendCalls.get())
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from transport_test_reply", Int::class.java))
    }

    @Test
    fun `quoted PEGO uses one logical provider send across HTTP replay`() {
        postTestReply("reply-key-2", "incoming-message-1").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACCEPTED") }
            jsonPath("$.providerMessageId") { value("provider-out-1") }
            jsonPath("$.message") { value("PEGO") }
            jsonPath("$.quotedMessageId") { value("incoming-message-1") }
            jsonPath("$.idempotentReplay") { value(false) }
            jsonPath("$.visualConfirmationRequired") { value(true) }
        }
        postTestReply("reply-key-1", "incoming-message-1").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACCEPTED") }
            jsonPath("$.idempotentReplay") { value(true) }
        }

        assertEquals(1, fake.sendCalls.get())
        val send = fake.requests.single { it.path.contains("/sendMessage/") }
        assertTrue(send.body.contains("\"message\":\"PEGO\""))
        assertTrue(send.body.contains("\"quotedMessageId\":\"incoming-message-1\""))
    }

    @Test
    fun `reusing idempotency key for another source is a conflict`() {
        postTestReply("collision-key", "incoming-message-1").andExpect {
            status { isOk() }
        }
        postTestReply("collision-key", "incoming-message-2").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONFLICT") }
        }

        assertEquals(1, fake.sendCalls.get())
    }

    @Test
    fun `cross collision between an older origin and newer idempotency key is a conflict`() {
        postTestReply("older-key", "older-message").andExpect {
            status { isOk() }
        }
        postTestReply("newer-key", "newer-message").andExpect {
            status { isOk() }
        }
        postTestReply("newer-key", "older-message").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONFLICT") }
        }

        assertEquals(2, fake.sendCalls.get())
    }

    @Test
    fun `accepted replay does not depend on a later provider state`() {
        postTestReply("stable-replay-key", "stable-message").andExpect {
            status { isOk() }
        }
        fake.respondToState(body = """{"stateInstance":"notAuthorized"}""")

        postTestReply("stable-replay-key", "stable-message").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACCEPTED") }
            jsonPath("$.idempotentReplay") { value(true) }
        }

        assertEquals(1, fake.sendCalls.get())
    }

    @Test
    fun `concurrent replay has one send winner`() {
        fake.respondToSend(body = """{"idMessage":"provider-concurrent"}""", delay = Duration.ofMillis(200))
        val workers = 8
        val barrier = CyclicBarrier(workers)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures =
                (1..workers).map {
                    executor.submit<SendTestReplyResponse> {
                        barrier.await(5, TimeUnit.SECONDS)
                        service.sendTestReply(
                            SendTestReplyRequest("120363000000000000@g.us", "incoming-concurrent"),
                            "concurrent-key",
                        )
                    }
                }
            val responses = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, fake.sendCalls.get())
            assertEquals(1, responses.count { !it.idempotentReplay })
            assertEquals(1, jdbcTemplate.queryForObject("select count(*) from transport_test_reply", Int::class.java))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `verify never infers visual quote success from provider acceptance`() {
        postWebhook(incomingWebhook())

        mockMvc
            .post("/api/v1/integrations/green-api/verify") {
                adminBearer()
            }.andExpect {
                status { isOk() }
                jsonPath("$.providerOperational") { value(true) }
                jsonPath("$.correctIdentifiersObserved") { value(true) }
                jsonPath("$.readyForTestReply") { value(true) }
                jsonPath("$.latestGroupWebhook.messageText") { value("teste shift catcher") }
                jsonPath("$.quotedReplyVisualStatus") { value("NOT_CONFIRMED") }
                jsonPath("$.verified") { value(false) }
            }
    }

    private fun postWebhook(body: String) =
        mockMvc.post("/api/v1/webhooks/green-api") {
            header("Authorization", "Bearer test-webhook-token")
            header("X-Correlation-Id", "webhook-test")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun postTestReply(
        idempotencyKey: String,
        quotedMessageId: String,
    ) = mockMvc.post("/api/v1/poc/send-test-reply") {
        adminBearer()
        header("Idempotency-Key", idempotencyKey)
        contentType = MediaType.APPLICATION_JSON
        content =
            """{"chatId":"120363000000000000@g.us","quotedMessageId":"$quotedMessageId"}"""
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.adminBearer() {
        header("Authorization", "Bearer test-admin-token")
    }

    private fun incomingWebhook(): String =
        """
        {
          "typeWebhook": "incomingMessageReceived",
          "instanceData": {"idInstance": 123456},
          "timestamp": 1787227200,
          "idMessage": "incoming-message-1",
          "senderData": {
            "chatId": "120363000000000000@g.us",
            "chatName": "Plantões",
            "sender": "5511999999999@c.us",
            "senderName": "Pessoa",
            "senderContactName": "Contato"
          },
          "messageData": {
            "typeMessage": "textMessage",
            "textMessageData": {"textMessage": "teste shift catcher"}
          }
        }
        """.trimIndent()

    companion object {
        private val fake = FakeGreenApiServer()

        @JvmStatic
        @DynamicPropertySource
        fun greenApiProperties(registry: DynamicPropertyRegistry) {
            registry.add("shift-catcher.green-api.api-url", fake::baseUrl)
        }

        @JvmStatic
        @AfterAll
        fun closeFake() {
            fake.close()
        }
    }
}
