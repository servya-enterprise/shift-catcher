package br.com.shiftcatcher.claim

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class ShiftClaimRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * Returns null when a claim already exists for the opportunity. The unique constraint on
     * `opportunity_id` is the real guard: concurrent callers race on the database, not on a check.
     */
    @Transactional
    fun create(claim: ShiftClaimWrite): ShiftClaim? =
        runCatching {
            jdbcTemplate.queryForObject(
                INSERT_SQL,
                ROW_MAPPER,
                claim.opportunityId,
                claim.mode.name,
                claim.chatId,
                claim.quotedMessageId,
                claim.ruleEvaluationId,
                Timestamp.from(claim.decidedAt),
            )
        }.getOrElse { failure ->
            if (failure is DuplicateKeyException) null else throw failure
        }

    fun findById(id: UUID): ShiftClaim? = jdbcTemplate.query("$SELECT_SQL where id = ?", ROW_MAPPER, id).firstOrNull()

    fun findByOpportunityId(opportunityId: UUID): ShiftClaim? =
        jdbcTemplate.query("$SELECT_SQL where opportunity_id = ?", ROW_MAPPER, opportunityId).firstOrNull()

    fun findRecent(limit: Int): List<ShiftClaim> =
        jdbcTemplate.query("$SELECT_SQL order by decided_at desc, id desc limit ?", ROW_MAPPER, limit)

    /** Guarded transition: the update only applies when the claim is still in an expected state. */
    @Transactional
    fun transition(
        id: UUID,
        from: Set<ClaimStatus>,
        to: ClaimStatus,
        providerMessageId: String? = null,
        failureCode: String? = null,
        at: Instant,
    ): ShiftClaim? =
        jdbcTemplate
            .query(
                TRANSITION_SQL,
                ROW_MAPPER,
                to.name,
                providerMessageId,
                failureCode,
                to.name,
                Timestamp.from(at),
                to.name,
                Timestamp.from(at),
                id,
                from.joinToString(",") { it.name },
            ).firstOrNull()

    @Transactional
    fun recordRetraction(
        id: UUID,
        at: java.time.Instant,
        reason: String?,
        failureCode: String?,
    ): ShiftClaim? =
        jdbcTemplate
            .query(
                RETRACTION_SQL,
                ROW_MAPPER,
                if (failureCode == null) ClaimStatus.RETRACTED.name else ClaimStatus.CLAIMED.name,
                Timestamp.from(at),
                reason,
                failureCode,
                id,
            ).firstOrNull()

    @Transactional
    fun incrementAttemptCount(id: UUID): Int =
        jdbcTemplate.queryForObject(
            "update shift_claim set attempt_count = attempt_count + 1, updated_at = current_timestamp " +
                "where id = ? returning attempt_count",
            Int::class.java,
            id,
        )!!

    private companion object {
        val SELECT_SQL =
            """
            select id, opportunity_id, status, mode, chat_id, quoted_message_id, message,
                   rule_evaluation_id, provider_message_id, attempt_count, decided_at, claimed_at,
                   failed_at, failure_code, version
              from shift_claim
            """.trimIndent()

        val INSERT_SQL =
            """
            insert into shift_claim (
                opportunity_id, status, mode, chat_id, quoted_message_id, message,
                rule_evaluation_id, decided_at
            ) values (?, 'CREATED', ?, ?, ?, 'PEGO', ?, ?)
            returning id, opportunity_id, status, mode, chat_id, quoted_message_id, message,
                      rule_evaluation_id, provider_message_id, attempt_count, decided_at,
                      claimed_at, failed_at, failure_code, version
            """.trimIndent()

        val RETRACTION_SQL =
            """
            update shift_claim
               set status = ?,
                   retracted_at = ?,
                   retraction_reason = ?,
                   retraction_failure_code = ?,
                   version = version + 1,
                   updated_at = current_timestamp
             where id = ?
               and status in ('CLAIMED', 'RETRACTING')
            returning id, opportunity_id, status, mode, chat_id, quoted_message_id, message,
                      rule_evaluation_id, provider_message_id, attempt_count, decided_at,
                      claimed_at, failed_at, failure_code, version
            """.trimIndent()

        val TRANSITION_SQL =
            """
            update shift_claim
               set status = ?,
                   provider_message_id = coalesce(?, provider_message_id),
                   failure_code = coalesce(?, failure_code),
                   claimed_at = case when ? = 'CLAIMED' then ? else claimed_at end,
                   failed_at = case when ? = 'FAILED' then ? else failed_at end,
                   version = version + 1,
                   updated_at = current_timestamp
             where id = ?
               and status = any (string_to_array(?, ','))
            returning id, opportunity_id, status, mode, chat_id, quoted_message_id, message,
                      rule_evaluation_id, provider_message_id, attempt_count, decided_at,
                      claimed_at, failed_at, failure_code, version
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                ShiftClaim(
                    id = resultSet.getObject("id", UUID::class.java),
                    opportunityId = resultSet.getObject("opportunity_id", UUID::class.java),
                    status = ClaimStatus.valueOf(resultSet.getString("status")),
                    mode = ClaimMode.valueOf(resultSet.getString("mode")),
                    chatId = resultSet.getString("chat_id"),
                    quotedMessageId = resultSet.getString("quoted_message_id"),
                    message = resultSet.getString("message"),
                    ruleEvaluationId = resultSet.getObject("rule_evaluation_id", UUID::class.java),
                    providerMessageId = resultSet.getString("provider_message_id"),
                    attemptCount = resultSet.getInt("attempt_count"),
                    decidedAt = resultSet.getTimestamp("decided_at").toInstant(),
                    claimedAt = resultSet.getTimestamp("claimed_at")?.toInstant(),
                    failedAt = resultSet.getTimestamp("failed_at")?.toInstant(),
                    failureCode = resultSet.getString("failure_code"),
                    version = resultSet.getInt("version"),
                )
            }
    }
}

/** `04-Domain/State-Machines.md` claim lifecycle. */
enum class ClaimStatus {
    CREATED,
    SENDING,
    RETRY_PENDING,
    PROVIDER_ACCEPTED,
    CLAIMED,
    FAILED,
    RETRACTING,
    RETRACTED,
    ;

    /** A sent message is the only thing that can be taken back. */
    fun isRetractable(): Boolean = this == CLAIMED
}

enum class ClaimMode {
    MANUAL,
    AUTO,
}

data class ShiftClaimWrite(
    val opportunityId: UUID,
    val mode: ClaimMode,
    val chatId: String,
    val quotedMessageId: String,
    val ruleEvaluationId: UUID?,
    val decidedAt: Instant,
)

data class ShiftClaim(
    val id: UUID,
    val opportunityId: UUID,
    val status: ClaimStatus,
    val mode: ClaimMode,
    val chatId: String,
    val quotedMessageId: String,
    val message: String,
    val ruleEvaluationId: UUID?,
    val providerMessageId: String?,
    val attemptCount: Int,
    val decidedAt: Instant,
    val claimedAt: Instant?,
    val failedAt: Instant?,
    val failureCode: String?,
    val version: Int,
)
