package br.com.shiftcatcher.integration.greenapi

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class TransportTestReplyRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findExisting(
        idempotencyKey: String,
        logicalKey: String,
    ): ReservedTestReply? {
        val byIdempotencyKey = findBy(IDEMPOTENCY_KEY_SQL, idempotencyKey)
        if (byIdempotencyKey != null) {
            return ReservedTestReply(
                record = byIdempotencyKey,
                created = false,
                keyCollision = byIdempotencyKey.logicalKey != logicalKey,
            )
        }

        return findBy(LOGICAL_KEY_SQL, logicalKey)?.let {
            ReservedTestReply(record = it, created = false, keyCollision = false)
        }
    }

    @Transactional
    fun reserve(
        idempotencyKey: String,
        logicalKey: String,
        chatId: String,
        quotedMessageId: String,
    ): ReservedTestReply {
        val inserted =
            jdbcTemplate
                .query(
                    INSERT_SQL,
                    ::mapRecord,
                    idempotencyKey,
                    logicalKey,
                    chatId,
                    quotedMessageId,
                ).firstOrNull()
        if (inserted != null) {
            return ReservedTestReply(inserted, created = true, keyCollision = false)
        }

        return findExisting(idempotencyKey, logicalKey) ?: error("Reserved test reply disappeared")
    }

    private fun findBy(
        sql: String,
        value: String,
    ): TestReplyRecord? = jdbcTemplate.query(sql, ::mapRecord, value).firstOrNull()

    fun markSendStarted(
        id: UUID,
        startedAt: Instant,
    ) {
        check(
            jdbcTemplate.update(
                "update transport_test_reply set send_started_at = ? where id = ? and status = 'PENDING'",
                Timestamp.from(startedAt),
                id,
            ) == 1,
        ) { "Test reply is no longer pending" }
    }

    fun markAccepted(
        id: UUID,
        providerMessageId: String,
        acceptedAt: Instant,
    ): TestReplyRecord =
        jdbcTemplate.queryForObject(
            ACCEPT_SQL,
            ::mapRecord,
            providerMessageId,
            Timestamp.from(acceptedAt),
            id,
        )

    fun markFailed(
        id: UUID,
        failureCode: String,
        failedAt: Instant,
    ) {
        jdbcTemplate.update(
            FAIL_SQL,
            failureCode,
            Timestamp.from(failedAt),
            id,
        )
    }

    private fun mapRecord(
        resultSet: ResultSet,
        rowNumber: Int,
    ): TestReplyRecord =
        TestReplyRecord(
            id = resultSet.getObject("id", UUID::class.java),
            idempotencyKey = resultSet.getString("idempotency_key"),
            logicalKey = resultSet.getString("logical_key"),
            chatId = resultSet.getString("chat_id"),
            quotedMessageId = resultSet.getString("quoted_message_id"),
            status = TestReplyStatus.valueOf(resultSet.getString("status")),
            providerMessageId = resultSet.getString("provider_message_id"),
            createdAt = resultSet.getTimestamp("created_at").toInstant(),
            sendStartedAt = resultSet.getTimestamp("send_started_at")?.toInstant(),
            providerAcceptedAt = resultSet.getTimestamp("provider_accepted_at")?.toInstant(),
        )

    private companion object {
        val COLUMNS =
            """
            id, idempotency_key, logical_key, chat_id, quoted_message_id, status,
            provider_message_id, created_at, send_started_at, provider_accepted_at
            """.trimIndent()

        val INSERT_SQL =
            """
            insert into transport_test_reply (
                idempotency_key, logical_key, chat_id, quoted_message_id, message, status
            ) values (?, ?, ?, ?, 'PEGO', 'PENDING')
            on conflict do nothing
            returning $COLUMNS
            """.trimIndent()

        val IDEMPOTENCY_KEY_SQL =
            """
            select $COLUMNS
              from transport_test_reply
             where idempotency_key = ?
            """.trimIndent()

        val LOGICAL_KEY_SQL =
            """
            select $COLUMNS
              from transport_test_reply
             where logical_key = ?
            """.trimIndent()

        val ACCEPT_SQL =
            """
            update transport_test_reply
               set status = 'ACCEPTED', provider_message_id = ?, provider_accepted_at = ?
             where id = ? and status = 'PENDING'
            returning $COLUMNS
            """.trimIndent()

        val FAIL_SQL =
            """
            update transport_test_reply
               set status = 'FAILED', failure_code = ?, failed_at = ?
             where id = ? and status = 'PENDING'
            """.trimIndent()
    }
}

data class ReservedTestReply(
    val record: TestReplyRecord,
    val created: Boolean,
    val keyCollision: Boolean,
)

data class TestReplyRecord(
    val id: UUID,
    val idempotencyKey: String,
    val logicalKey: String,
    val chatId: String,
    val quotedMessageId: String,
    val status: TestReplyStatus,
    val providerMessageId: String?,
    val createdAt: Instant,
    val sendStartedAt: Instant?,
    val providerAcceptedAt: Instant?,
)

enum class TestReplyStatus {
    PENDING,
    ACCEPTED,
    FAILED,
}
