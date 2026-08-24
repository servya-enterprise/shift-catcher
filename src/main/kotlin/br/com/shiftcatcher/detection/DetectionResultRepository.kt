package br.com.shiftcatcher.detection

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class DetectionResultRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun upsert(record: DetectionResultWrite): DetectionResultRecord =
        jdbcTemplate.queryForObject(
            UPSERT_SQL,
            ROW_MAPPER,
            record.messageId,
            record.candidate,
            record.score,
            record.signals.joinToString(","),
            Timestamp.from(record.detectionStartedAt),
            Timestamp.from(record.completedAt),
        )!!

    fun findByMessageId(messageId: UUID): DetectionResultRecord? =
        jdbcTemplate.query("$SELECT_SQL where message_id = ?", ROW_MAPPER, messageId).firstOrNull()

    private companion object {
        val SELECT_SQL =
            """
            select id, message_id, candidate, score, signals, detection_started_at, completed_at
              from detection_result
            """.trimIndent()

        val UPSERT_SQL =
            """
            insert into detection_result (
                message_id, candidate, score, signals, detection_started_at, completed_at
            ) values (?, ?, ?, ?, ?, ?)
            on conflict (message_id) do update
               set candidate = excluded.candidate,
                   score = excluded.score,
                   signals = excluded.signals,
                   detection_started_at = excluded.detection_started_at,
                   completed_at = excluded.completed_at,
                   updated_at = current_timestamp
            returning id, message_id, candidate, score, signals, detection_started_at, completed_at
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                DetectionResultRecord(
                    id = resultSet.getObject("id", UUID::class.java),
                    messageId = resultSet.getObject("message_id", UUID::class.java),
                    candidate = resultSet.getBoolean("candidate"),
                    score = resultSet.getBigDecimal("score"),
                    signals = resultSet.getString("signals").split(",").filter { it.isNotBlank() },
                    detectionStartedAt = resultSet.getTimestamp("detection_started_at").toInstant(),
                    completedAt = resultSet.getTimestamp("completed_at").toInstant(),
                )
            }
    }
}

data class DetectionResultWrite(
    val messageId: UUID,
    val candidate: Boolean,
    val score: BigDecimal,
    val signals: List<String>,
    val detectionStartedAt: Instant,
    val completedAt: Instant,
)

data class DetectionResultRecord(
    val id: UUID,
    val messageId: UUID,
    val candidate: Boolean,
    val score: BigDecimal,
    val signals: List<String>,
    val detectionStartedAt: Instant,
    val completedAt: Instant,
)
