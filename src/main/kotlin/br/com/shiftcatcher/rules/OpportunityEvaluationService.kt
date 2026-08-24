package br.com.shiftcatcher.rules

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.foundation.http.ApiProblemException
import br.com.shiftcatcher.group.AllowedGroupRepository
import br.com.shiftcatcher.messaging.IncomingMessageRepository
import br.com.shiftcatcher.shift.OpportunityStatus
import br.com.shiftcatcher.shift.ShiftOpportunity
import br.com.shiftcatcher.shift.ShiftOpportunityRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Applies the active rule set to an opportunity. This never runs inside the webhook request:
 * `03-Integrations/Webhook-Contract.md` forbids rules there, which is why ingestion stops at
 * `EVALUATING` and the transition out of it is always an explicit call.
 */
@Service
class OpportunityEvaluationService(
    private val ruleEngine: RuleEngine,
    private val ruleSetService: RuleSetService,
    private val evaluationRepository: RuleEvaluationRepository,
    private val opportunityRepository: ShiftOpportunityRepository,
    private val groupRepository: AllowedGroupRepository,
    private val messageRepository: IncomingMessageRepository,
    private val properties: ShiftCatcherProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun reevaluate(opportunityId: String): EvaluationResponse {
        val opportunity = load(opportunityId)
        requireOpenForEvaluation(opportunity)

        val active = ruleSetService.activeDefinition()
        val evaluatedAt = clock.instant()
        val outcome =
            if (active == null) {
                // No configured policy is not an approval.
                RuleOutcome(
                    result = EvaluationResult.REVIEW_REQUIRED,
                    reasons = listOf(RuleReason.NO_ACTIVE_RULE_SET),
                    autoClaimAllowed = false,
                )
            } else {
                ruleEngine.evaluate(contextFor(opportunity, active, evaluatedAt))
            }

        val record =
            evaluationRepository.record(
                RuleEvaluationWrite(
                    opportunityId = opportunity.id,
                    ruleSetId = active?.id,
                    ruleSetVersion = active?.version,
                    result = outcome.result,
                    reasons = outcome.reasons,
                    autoClaimAllowed = outcome.autoClaimAllowed,
                    evaluatedAt = evaluatedAt,
                ),
            )
        val updated =
            opportunityRepository.updateStatus(
                id = opportunity.id,
                expectedVersion = opportunity.version,
                status = outcome.result.toOpportunityStatus(),
                resolutionReason = outcome.reasons.firstOrNull(),
            ) ?: throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "STALE_VERSION",
                title = "Stale version",
                message = "The opportunity was modified while it was being evaluated; retry",
            )

        return EvaluationResponse(
            opportunityId = updated.id.toString(),
            status = updated.status,
            result = record.result,
            reasons = record.reasons,
            autoClaimAllowed = record.autoClaimAllowed,
            ruleSetVersion = record.ruleSetVersion,
            evaluatedAt = record.evaluatedAt,
            simulated = false,
        )
    }

    /**
     * `EP-032`: answers "what would this rule set do?" without persisting an evaluation or moving a
     * single opportunity, so a draft can be inspected before it is ever activated.
     */
    fun simulate(
        ruleSetId: String,
        request: SimulateRequest,
    ): SimulationResponse {
        val ruleSet = ruleSetService.definitionOf(ruleSetId)
        val now = clock.instant()
        val opportunities =
            request.opportunityIds
                ?.map { id -> load(id) }
                ?: opportunityRepository.findRecent(MAX_SIMULATION_SIZE)

        val results =
            opportunities.map { opportunity ->
                val outcome = ruleEngine.evaluate(contextFor(opportunity, ruleSet, now))
                EvaluationResponse(
                    opportunityId = opportunity.id.toString(),
                    // The stored status is reported unchanged: a simulation moves nothing.
                    status = opportunity.status,
                    result = outcome.result,
                    reasons = outcome.reasons,
                    autoClaimAllowed = outcome.autoClaimAllowed,
                    ruleSetVersion = ruleSet.version,
                    evaluatedAt = now,
                    simulated = true,
                )
            }
        return SimulationResponse(
            ruleSetId = ruleSet.id.toString(),
            ruleSetVersion = ruleSet.version,
            evaluated = results.size,
            eligible = results.count { it.result == EvaluationResult.ELIGIBLE },
            rejected = results.count { it.result == EvaluationResult.REJECTED },
            reviewRequired = results.count { it.result == EvaluationResult.REVIEW_REQUIRED },
            results = results,
        )
    }

    private fun contextFor(
        opportunity: ShiftOpportunity,
        ruleSet: ActiveRuleSet,
        now: Instant,
    ): EvaluationContext {
        val group = opportunity.groupId?.let { groupRepository.findById(it) }
        val message = messageRepository.findById(opportunity.sourceMessageId)
        return EvaluationContext(
            opportunity = opportunity,
            definition = ruleSet.definition,
            groupEnabled = group?.enabled ?: false,
            groupAutoClaimEnabled = group?.autoClaimEnabled ?: false,
            messageTimestamp = message?.providerTimestamp ?: opportunity.detectedAt,
            // Never observed here: evaluation judges the offer, the claim engine judges the provider.
            instanceOperational = null,
            now = now,
            timezone = properties.detection.timezone,
        )
    }

    private fun requireOpenForEvaluation(opportunity: ShiftOpportunity) {
        val settled =
            opportunity.status in
                setOf(
                    OpportunityStatus.CLAIM_PENDING,
                    OpportunityStatus.CLAIMED,
                    OpportunityStatus.CLAIM_FAILED,
                    OpportunityStatus.EXPIRED,
                )
        val manuallyIgnored =
            opportunity.status == OpportunityStatus.REJECTED && opportunity.resolutionReason == MANUALLY_IGNORED
        if (settled || manuallyIgnored) {
            throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Opportunity is not open for evaluation",
                message = "An opportunity that is ${opportunity.status} cannot be re-evaluated",
            )
        }
    }

    private fun EvaluationResult.toOpportunityStatus(): OpportunityStatus =
        when (this) {
            EvaluationResult.ELIGIBLE -> OpportunityStatus.ELIGIBLE
            EvaluationResult.REJECTED -> OpportunityStatus.REJECTED
            EvaluationResult.REVIEW_REQUIRED -> OpportunityStatus.REVIEW_REQUIRED
        }

    private fun load(opportunityId: String): ShiftOpportunity =
        opportunityRepository.findById(parseId(opportunityId))
            ?: throw ApiProblemException(
                status = HttpStatus.NOT_FOUND,
                code = "RESOURCE_NOT_FOUND",
                title = "Opportunity not found",
                message = "No shift opportunity matches the supplied identifier",
            )

    private fun parseId(opportunityId: String): UUID =
        runCatching { UUID.fromString(opportunityId) }
            .getOrElse { throw IllegalArgumentException("opportunityId must be a UUID") }

    private companion object {
        const val MANUALLY_IGNORED = "MANUALLY_IGNORED"
        const val MAX_SIMULATION_SIZE = 100
    }
}

data class SimulateRequest(
    val opportunityIds: List<String>? = null,
)

data class EvaluationResponse(
    val opportunityId: String,
    val status: OpportunityStatus,
    val result: EvaluationResult,
    val reasons: List<String>,
    val autoClaimAllowed: Boolean,
    val ruleSetVersion: Int?,
    val evaluatedAt: Instant,
    val simulated: Boolean,
)

data class SimulationResponse(
    val ruleSetId: String,
    val ruleSetVersion: Int,
    val evaluated: Int,
    val eligible: Int,
    val rejected: Int,
    val reviewRequired: Int,
    val results: List<EvaluationResponse>,
)
