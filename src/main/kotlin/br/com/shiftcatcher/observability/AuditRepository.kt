package br.com.shiftcatcher.observability

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `05-Data/Audit-and-Observability.md`: append-only. This repository deliberately exposes no update
 * or delete, so the trail cannot be rewritten from inside the application.
 */
@Repository
class AuditRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun record(event: AuditEventWrite) {
        jdbcTemplate.update(
            INSERT_SQL,
            event.aggregateType,
            event.aggregateId,
            event.eventType,
            event.detail?.take(512),
            event.correlationId,
            Timestamp.from(event.occurredAt),
        )
    }

    fun findRecent(limit: Int): List<AuditEventRecord> =
        jdbcTemplate.query("$SELECT_SQL order by occurred_at desc, id desc limit ?", ROW_MAPPER, limit)

    private companion object {
        val SELECT_SQL =
            """
            select id, aggregate_type, aggregate_id, event_type, detail, correlation_id, occurred_at
              from audit_event
            """.trimIndent()

        val INSERT_SQL =
            """
            insert into audit_event (
                aggregate_type, aggregate_id, event_type, detail, correlation_id, occurred_at
            ) values (?, ?, ?, ?, ?, ?)
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                AuditEventRecord(
                    id = resultSet.getObject("id", UUID::class.java),
                    aggregateType = resultSet.getString("aggregate_type"),
                    aggregateId = resultSet.getObject("aggregate_id", UUID::class.java),
                    eventType = resultSet.getString("event_type"),
                    detail = resultSet.getString("detail"),
                    correlationId = resultSet.getString("correlation_id"),
                    occurredAt = resultSet.getTimestamp("occurred_at").toInstant(),
                )
            }
    }
}

data class AuditEventWrite(
    val aggregateType: String,
    val aggregateId: UUID?,
    val eventType: String,
    val detail: String?,
    val correlationId: String? = null,
    val occurredAt: Instant,
)

data class AuditEventRecord(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID?,
    val eventType: String,
    val detail: String?,
    val correlationId: String?,
    val occurredAt: Instant,
)
