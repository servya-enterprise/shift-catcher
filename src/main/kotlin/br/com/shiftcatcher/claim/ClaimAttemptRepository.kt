package br.com.shiftcatcher.claim

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class ClaimAttemptRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    /** Append-only: every concrete provider call leaves a row, successful or not. */
    @Transactional
    fun record(attempt: ClaimAttemptWrite): ClaimAttempt =
        jdbcTemplate.queryForObject(
            INSERT_SQL,
            ROW_MAPPER,
            attempt.claimId,
            attempt.attemptNumber,
            Timestamp.from(attempt.startedAt),
            Timestamp.from(attempt.completedAt),
            attempt.providerResponseId,
            attempt.result.name,
            attempt.failureCode,
            attempt.latencyMs,
        )!!

    fun findByClaimId(claimId: UUID): List<ClaimAttempt> =
        jdbcTemplate.query("$SELECT_SQL where claim_id = ? order by attempt_number", ROW_MAPPER, claimId)

    private companion object {
        val SELECT_SQL =
            """
            select id, claim_id, attempt_number, started_at, completed_at, provider_response_id,
                   result, failure_code, latency_ms
              from claim_attempt
            """.trimIndent()

        val INSERT_SQL =
            """
            insert into claim_attempt (
                claim_id, attempt_number, started_at, completed_at, provider_response_id,
                result, failure_code, latency_ms
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            returning id, claim_id, attempt_number, started_at, completed_at, provider_response_id,
                      result, failure_code, latency_ms
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                ClaimAttempt(
                    id = resultSet.getObject("id", UUID::class.java),
                    claimId = resultSet.getObject("claim_id", UUID::class.java),
                    attemptNumber = resultSet.getInt("attempt_number"),
                    startedAt = resultSet.getTimestamp("started_at").toInstant(),
                    completedAt = resultSet.getTimestamp("completed_at")?.toInstant(),
                    providerResponseId = resultSet.getString("provider_response_id"),
                    result = AttemptResult.valueOf(resultSet.getString("result")),
                    failureCode = resultSet.getString("failure_code"),
                    latencyMs = resultSet.getObject("latency_ms") as? Int,
                )
            }
    }
}

enum class AttemptResult {
    ACCEPTED,
    TRANSIENT_FAILURE,
    PERMANENT_FAILURE,
}

data class ClaimAttemptWrite(
    val claimId: UUID,
    val attemptNumber: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val providerResponseId: String?,
    val result: AttemptResult,
    val failureCode: String?,
    val latencyMs: Int,
)

data class ClaimAttempt(
    val id: UUID,
    val claimId: UUID,
    val attemptNumber: Int,
    val startedAt: Instant,
    val completedAt: Instant?,
    val providerResponseId: String?,
    val result: AttemptResult,
    val failureCode: String?,
    val latencyMs: Int?,
)
