package br.com.shiftcatcher.reliability

import br.com.shiftcatcher.PostgresTestConfiguration
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
import org.springframework.test.web.servlet.post
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Retention is the only code here that destroys data, so what it must never do is asserted before
 * what it does.
 *
 * The message chain is redacted rather than deleted, and the reason is testable rather than
 * rhetorical: the dedupe key has to keep working. A webhook redelivered after its content is gone
 * must still be recognised as the same message, or an offer from six months ago walks back in as new.
 */
@Import(PostgresTestConfiguration::class)
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
class RetentionIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val service: RetentionService,
) {
    @BeforeEach
    fun reset() {
        jdbcTemplate.update("delete from audit_event")
        jdbcTemplate.update("delete from claim_attempt")
        jdbcTemplate.update("delete from outbox_event")
        jdbcTemplate.update("delete from shift_claim")
        jdbcTemplate.update("delete from rule_evaluation")
        jdbcTemplate.update("delete from shift_opportunity")
        jdbcTemplate.update("delete from detection_result")
        jdbcTemplate.update("delete from incoming_message")
        jdbcTemplate.update("delete from incoming_provider_event")
        jdbcTemplate.update("delete from allowed_group")
        jdbcTemplate.update("delete from benchmark_run")
    }

    @Test
    fun `the default configuration counts and changes nothing`() {
        ingest("old-1")
        backdate(DAYS_OLD)

        val summary = service.runOnce()

        assertEquals(true, summary.dryRun, "armed only on purpose")
        assertEquals(1, summary.messagesRedacted, "it says what it would do")
        assertEquals(ORIGINAL_TEXT, messageText(), "and does not do it")
        assertNull(redactedAt(), "nothing was marked redacted")
    }

    @Test
    fun `armed, it takes the words and leaves the row`() {
        ingest("old-1")
        backdate(DAYS_OLD)

        val redacted = service.redactMessages(Instant.now().minusSeconds(CUTOFF_SECONDS), dryRun = false)

        assertEquals(1, redacted)
        assertEquals("REDACTED", messageText())
        assertNotNull(redactedAt(), "a redacted row is distinguishable from one that arrived empty")
        assertEquals(1, countOf("incoming_message"), "the row itself survives")
    }

    @Test
    fun `a message redelivered after redaction is still recognised as the same one`() {
        ingest("old-1")
        backdate(DAYS_OLD)
        service.redactProviderEvents(Instant.now().minusSeconds(CUTOFF_SECONDS), dryRun = false)

        // The same webhook again. Six months later the content is gone, but the identity is not.
        ingest("old-1")

        assertEquals(1, countOf("incoming_provider_event"), "no second row for the same message")
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select duplicate_count from incoming_provider_event",
                Int::class.java,
            ),
            "and it was counted as the duplicate it is",
        )
    }

    @Test
    fun `recent messages are left entirely alone`() {
        ingest("fresh-1")

        service.redactMessages(Instant.now().minusSeconds(CUTOFF_SECONDS), dryRun = false)

        assertEquals(ORIGINAL_TEXT, messageText())
        assertNull(redactedAt())
    }

    @Test
    fun `redacting twice does not touch what it already did`() {
        ingest("old-1")
        backdate(DAYS_OLD)
        val cutoff = Instant.now().minusSeconds(CUTOFF_SECONDS)

        assertEquals(1, service.redactMessages(cutoff, dryRun = false))
        assertEquals(0, service.redactMessages(cutoff, dryRun = false), "the second pass finds nothing left")
    }

    @Test
    fun `only spent outbox intents are deleted`() {
        ingest("old-1")
        val opportunityId = jdbcTemplate.queryForObject("select id from shift_opportunity", java.util.UUID::class.java)
        jdbcTemplate.update(
            """
            insert into outbox_event (aggregate_type, aggregate_id, event_type, payload, status, completed_at)
            values ('SHIFT_CLAIM', ?, 'SEND_CLAIM_MESSAGE', '{}'::jsonb, 'DONE', current_timestamp - interval '200 days')
            """.trimIndent(),
            opportunityId,
        )
        jdbcTemplate.update(
            """
            insert into outbox_event (aggregate_type, aggregate_id, event_type, payload, status, available_at)
            values ('SHIFT_CLAIM', gen_random_uuid(), 'SEND_CLAIM_MESSAGE', '{}'::jsonb, 'PENDING',
                    current_timestamp - interval '200 days')
            """.trimIndent(),
        )

        val deleted = service.deleteSpentOutbox(Instant.now().minusSeconds(CUTOFF_SECONDS), dryRun = false)

        assertEquals(1, deleted)
        // Pending work is still work, however old it looks.
        assertEquals(1, countOf("outbox_event"))
        assertEquals(
            "PENDING",
            jdbcTemplate.queryForObject("select status from outbox_event", String::class.java),
        )
    }

    @Test
    fun `old audit rows go`() {
        jdbcTemplate.update(
            """
            insert into audit_event (aggregate_type, event_type, detail, occurred_at)
            values ('TEST', 'OLD', 'x', current_timestamp - interval '200 days'),
                   ('TEST', 'RECENT', 'x', current_timestamp)
            """.trimIndent(),
        )

        assertEquals(1, service.deleteAudit(Instant.now().minusSeconds(CUTOFF_SECONDS), dryRun = false))
        assertEquals(
            "RECENT",
            jdbcTemplate.queryForObject("select event_type from audit_event", String::class.java),
        )
    }

    private fun backdate(days: Int) {
        jdbcTemplate.update("update incoming_message set received_at = current_timestamp - make_interval(days => ?)", days)
        jdbcTemplate.update(
            "update incoming_provider_event set webhook_received_at = current_timestamp - make_interval(days => ?)",
            days,
        )
    }

    private fun messageText(): String? = jdbcTemplate.queryForObject("select text from incoming_message", String::class.java)

    private fun redactedAt(): Instant? =
        jdbcTemplate
            .queryForObject("select redacted_at from incoming_message", java.sql.Timestamp::class.java)
            ?.toInstant()

    private fun countOf(table: String): Int = jdbcTemplate.queryForObject("select count(*) from $table", Int::class.java) ?: 0

    private fun ingest(providerMessageId: String) {
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
                        "senderName": "Colega"
                      },
                      "messageData": {
                        "typeMessage": "textMessage",
                        "textMessageData": {"textMessage": "$ORIGINAL_TEXT"}
                      }
                    }
                    """.trimIndent()
            }.andExpect { status { isOk() } }
    }

    private fun MockHttpServletRequestDsl.adminBearer() {
        header("Authorization", "Bearer test-admin-token")
    }

    private companion object {
        const val GROUP_CHAT_ID = "120363000000000000@g.us"
        const val ORIGINAL_TEXT = "Plantao 25/08 19-07 no PS Central"
        const val DAYS_OLD = 200
        const val CUTOFF_SECONDS = 100L * 24 * 60 * 60
    }
}
