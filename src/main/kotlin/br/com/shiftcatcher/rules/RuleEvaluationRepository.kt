package br.com.shiftcatcher.rules

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class RuleEvaluationRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * Evaluations are append-only: the rule set version is stored alongside the verdict so a past
     * decision stays explainable after that version has been superseded.
     */
    @Transactional
    fun record(evaluation: RuleEvaluationWrite): RuleEvaluationRecord =
        jdbcTemplate.queryForObject(
            INSERT_SQL,
            ROW_MAPPER,
            evaluation.opportunityId,
            evaluation.ruleSetId,
            evaluation.ruleSetVersion,
            evaluation.result.name,
            evaluation.reasons.joinToString(","),
            evaluation.autoClaimAllowed,
            Timestamp.from(evaluation.evaluatedAt),
        )!!

    fun findLatestForOpportunity(opportunityId: UUID): RuleEvaluationRecord? =
        jdbcTemplate
            .query(
                "$SELECT_SQL where opportunity_id = ? order by evaluated_at desc, id desc limit 1",
                ROW_MAPPER,
                opportunityId,
            ).firstOrNull()

    private companion object {
        val SELECT_SQL =
            """
            select id, opportunity_id, rule_set_id, rule_set_version, result, reasons,
                   auto_claim_allowed, evaluated_at
              from rule_evaluation
            """.trimIndent()

        val INSERT_SQL =
            """
            insert into rule_evaluation (
                opportunity_id, rule_set_id, rule_set_version, result, reasons,
                auto_claim_allowed, evaluated_at
            ) values (?, ?, ?, ?, ?, ?, ?)
            returning id, opportunity_id, rule_set_id, rule_set_version, result, reasons,
                      auto_claim_allowed, evaluated_at
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                RuleEvaluationRecord(
                    id = resultSet.getObject("id", UUID::class.java),
                    opportunityId = resultSet.getObject("opportunity_id", UUID::class.java),
                    ruleSetId = resultSet.getObject("rule_set_id", UUID::class.java),
                    ruleSetVersion = resultSet.getObject("rule_set_version") as? Int,
                    result = EvaluationResult.valueOf(resultSet.getString("result")),
                    reasons = resultSet.getString("reasons").split(",").filter { it.isNotBlank() },
                    autoClaimAllowed = resultSet.getBoolean("auto_claim_allowed"),
                    evaluatedAt = resultSet.getTimestamp("evaluated_at").toInstant(),
                )
            }
    }
}

data class RuleEvaluationWrite(
    val opportunityId: UUID,
    val ruleSetId: UUID?,
    val ruleSetVersion: Int?,
    val result: EvaluationResult,
    val reasons: List<String>,
    val autoClaimAllowed: Boolean,
    val evaluatedAt: Instant,
)

data class RuleEvaluationRecord(
    val id: UUID,
    val opportunityId: UUID,
    val ruleSetId: UUID?,
    val ruleSetVersion: Int?,
    val result: EvaluationResult,
    val reasons: List<String>,
    val autoClaimAllowed: Boolean,
    val evaluatedAt: Instant,
)
