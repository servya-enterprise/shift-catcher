package br.com.shiftcatcher.reliability

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `DEC-006`: the intent to send is written in the same transaction as the claim, and a worker picks
 * it up afterwards. Nothing outside this table decides that a message should go out.
 */
@Repository
class OutboxRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    /** Returns null when an intent already exists for this aggregate, which keeps the send logical. */
    @Transactional
    fun enqueueSendClaim(
        claimId: UUID,
        payloadJson: String,
    ): OutboxEvent? =
        runCatching {
            jdbcTemplate.queryForObject(INSERT_SQL, ROW_MAPPER, claimId, payloadJson)
        }.getOrElse { failure ->
            if (failure is DuplicateKeyException) null else throw failure
        }

    /**
     * Claims one due event for this worker by moving it to `PROCESSING` with a lease. The conditional
     * update is what stops two workers from sending the same message.
     */
    @Transactional
    fun leaseNext(
        now: Instant,
        leaseUntil: Instant,
    ): OutboxEvent? =
        jdbcTemplate
            .query(LEASE_SQL, ROW_MAPPER, Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now))
            .firstOrNull()

    @Transactional
    fun markDone(
        id: UUID,
        at: Instant,
    ) {
        jdbcTemplate.update(DONE_SQL, Timestamp.from(at), id)
    }

    @Transactional
    fun markFailed(
        id: UUID,
        lastError: String,
        at: Instant,
    ) {
        jdbcTemplate.update(FAILED_SQL, lastError.take(256), Timestamp.from(at), id)
    }

    /** `EP-026`: makes a failed intent due again without creating a second one. */
    @Transactional
    fun requeue(
        id: UUID,
        availableAt: Instant,
    ): Int = jdbcTemplate.update(REQUEUE_SQL, Timestamp.from(availableAt), id)

    fun findByClaimId(claimId: UUID): OutboxEvent? =
        jdbcTemplate.query("$SELECT_SQL where aggregate_id = ?", ROW_MAPPER, claimId).firstOrNull()

    private companion object {
        val SELECT_SQL =
            """
            select id, aggregate_type, aggregate_id, event_type, payload::text as payload, status,
                   attempts, available_at, locked_until, last_error
              from outbox_event
            """.trimIndent()

        val INSERT_SQL =
            """
            insert into outbox_event (
                aggregate_type, aggregate_id, event_type, payload, status
            ) values ('SHIFT_CLAIM', ?, 'SEND_CLAIM_MESSAGE', ?::jsonb, 'PENDING')
            returning id, aggregate_type, aggregate_id, event_type, payload::text as payload, status,
                      attempts, available_at, locked_until, last_error
            """.trimIndent()

        val LEASE_SQL =
            """
            update outbox_event
               set status = 'PROCESSING',
                   attempts = attempts + 1,
                   locked_until = ?,
                   updated_at = current_timestamp
             where id = (
                 select id
                   from outbox_event
                  where available_at <= ?
                    and (
                        status = 'PENDING'
                        or (status = 'PROCESSING' and locked_until is not null and locked_until < ?)
                    )
                  order by available_at
                  for update skip locked
                  limit 1
             )
            returning id, aggregate_type, aggregate_id, event_type, payload::text as payload, status,
                      attempts, available_at, locked_until, last_error
            """.trimIndent()

        val DONE_SQL =
            """
            update outbox_event
               set status = 'DONE', locked_until = null, completed_at = ?, updated_at = current_timestamp
             where id = ?
            """.trimIndent()

        val FAILED_SQL =
            """
            update outbox_event
               set status = 'FAILED', locked_until = null, last_error = ?, completed_at = ?,
                   updated_at = current_timestamp
             where id = ?
            """.trimIndent()

        val REQUEUE_SQL =
            """
            update outbox_event
               set status = 'PENDING', locked_until = null, completed_at = null, available_at = ?,
                   updated_at = current_timestamp
             where id = ?
               and status = 'FAILED'
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                OutboxEvent(
                    id = resultSet.getObject("id", UUID::class.java),
                    aggregateType = resultSet.getString("aggregate_type"),
                    aggregateId = resultSet.getObject("aggregate_id", UUID::class.java),
                    eventType = resultSet.getString("event_type"),
                    payloadJson = resultSet.getString("payload"),
                    status = OutboxStatus.valueOf(resultSet.getString("status")),
                    attempts = resultSet.getInt("attempts"),
                    availableAt = resultSet.getTimestamp("available_at").toInstant(),
                    lockedUntil = resultSet.getTimestamp("locked_until")?.toInstant(),
                    lastError = resultSet.getString("last_error"),
                )
            }
    }
}

enum class OutboxStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED,
}

data class OutboxEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val payloadJson: String,
    val status: OutboxStatus,
    val attempts: Int,
    val availableAt: Instant,
    val lockedUntil: Instant?,
    val lastError: String?,
)
