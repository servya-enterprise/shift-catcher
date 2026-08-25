package br.com.shiftcatcher.benchmark

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class BenchmarkRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * Returns null when a run is already in flight. The partial unique index is the real guard, so
     * two callers race on the database rather than on a check.
     */
    @Transactional
    fun start(
        label: String?,
        corpusSize: Int,
        aiEnabled: Boolean,
        startedAt: Instant,
    ): BenchmarkRun? =
        runCatching {
            jdbcTemplate.queryForObject(
                INSERT_SQL,
                ROW_MAPPER,
                label,
                corpusSize,
                aiEnabled,
                Timestamp.from(startedAt),
            )
        }.getOrElse { failure -> if (failure is DuplicateKeyException) null else throw failure }

    fun findById(id: UUID): BenchmarkRun? = jdbcTemplate.query("$SELECT_SQL where id = ?", ROW_MAPPER, id).firstOrNull()

    @Transactional
    fun complete(
        id: UUID,
        reportJson: String,
        completedAt: Instant,
    ) {
        jdbcTemplate.update(
            "update benchmark_run set status = 'COMPLETED', report = ?::jsonb, completed_at = ? where id = ?",
            reportJson,
            Timestamp.from(completedAt),
            id,
        )
    }

    /** A run that dies must not hold the single-active slot for ever. */
    @Transactional
    fun fail(
        id: UUID,
        failure: String,
        completedAt: Instant,
    ) {
        jdbcTemplate.update(
            "update benchmark_run set status = 'FAILED', failure = ?, completed_at = ? where id = ?",
            failure.take(MAX_FAILURE),
            Timestamp.from(completedAt),
            id,
        )
    }

    private companion object {
        const val MAX_FAILURE = 2000

        val SELECT_SQL =
            """
            select id, status, label, corpus_size, ai_enabled, started_at, completed_at, failure, report
              from benchmark_run
            """.trimIndent()

        val INSERT_SQL =
            """
            insert into benchmark_run (status, label, corpus_size, ai_enabled, started_at)
            values ('RUNNING', ?, ?, ?, ?)
            returning id, status, label, corpus_size, ai_enabled, started_at, completed_at, failure, report
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                BenchmarkRun(
                    id = resultSet.getObject("id", UUID::class.java),
                    status = BenchmarkStatus.valueOf(resultSet.getString("status")),
                    label = resultSet.getString("label"),
                    corpusSize = resultSet.getInt("corpus_size"),
                    aiEnabled = resultSet.getBoolean("ai_enabled"),
                    startedAt = resultSet.getTimestamp("started_at").toInstant(),
                    completedAt = resultSet.getTimestamp("completed_at")?.toInstant(),
                    failure = resultSet.getString("failure"),
                    reportJson = resultSet.getString("report"),
                )
            }
    }
}

data class BenchmarkRun(
    val id: UUID,
    val status: BenchmarkStatus,
    val label: String?,
    val corpusSize: Int,
    val aiEnabled: Boolean,
    val startedAt: Instant,
    val completedAt: Instant?,
    val failure: String?,
    val reportJson: String?,
)
