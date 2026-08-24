package br.com.shiftcatcher.claim

import br.com.shiftcatcher.foundation.http.ApiProblemException
import br.com.shiftcatcher.group.AllowedGroupRepository
import br.com.shiftcatcher.integration.greenapi.DeleteMessage
import br.com.shiftcatcher.integration.greenapi.WhatsAppMessageRetractor
import br.com.shiftcatcher.messaging.IncomingMessageRepository
import br.com.shiftcatcher.observability.AuditEventWrite
import br.com.shiftcatcher.observability.AuditRepository
import br.com.shiftcatcher.reliability.OutboxRepository
import br.com.shiftcatcher.reliability.ProviderHealthGate
import br.com.shiftcatcher.rules.EvaluationResult
import br.com.shiftcatcher.rules.RuleEvaluationRepository
import br.com.shiftcatcher.shift.OpportunityStatus
import br.com.shiftcatcher.shift.ShiftOpportunity
import br.com.shiftcatcher.shift.ShiftOpportunityRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * `04-Domain/Claim-Engine.md`. Everything this service does happens in one transaction: the guarded
 * transition out of `ELIGIBLE`, the claim row, and the outbox intent. It never talks to WhatsApp —
 * `DEC-006` puts the send behind the outbox worker.
 */
@Service
class ClaimService(
    private val claimRepository: ShiftClaimRepository,
    private val attemptRepository: ClaimAttemptRepository,
    private val outboxRepository: OutboxRepository,
    private val opportunityRepository: ShiftOpportunityRepository,
    private val evaluationRepository: RuleEvaluationRepository,
    private val groupRepository: AllowedGroupRepository,
    private val messageRepository: IncomingMessageRepository,
    private val providerHealth: ProviderHealthGate,
    private val messageRetractor: WhatsAppMessageRetractor,
    private val auditRepository: AuditRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun claim(
        opportunityId: String,
        request: ClaimRequest?,
    ): ClaimResponse {
        val mode = request?.mode ?: ClaimMode.MANUAL
        val opportunity = loadOpportunity(opportunityId)

        claimRepository.findByOpportunityId(opportunity.id)?.let {
            // `04-Domain/Claim-Engine.md`: two simultaneous claims leave one winner and one 409.
            throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Opportunity already claimed",
                message = "A claim already exists for this opportunity",
            )
        }
        if (opportunity.status != OpportunityStatus.ELIGIBLE) {
            throw notClaimable("An opportunity that is ${opportunity.status} cannot be claimed")
        }

        val message =
            messageRepository.findById(opportunity.sourceMessageId)
                ?: throw ApiProblemException(
                    status = HttpStatus.CONFLICT,
                    code = "QUOTE_MESSAGE_UNKNOWN",
                    title = "Source message unavailable",
                    message = "The message this opportunity came from is no longer available to quote",
                )
        val group =
            opportunity.groupId?.let { groupRepository.findById(it) }
                ?: throw notClaimable("The opportunity is not attached to an allowed group")
        if (!group.enabled) {
            throw notClaimable("The group is disabled")
        }

        val evaluation = evaluationRepository.findLatestForOpportunity(opportunity.id)
        if (evaluation == null || evaluation.result != EvaluationResult.ELIGIBLE) {
            throw notClaimable("The opportunity has no valid ELIGIBLE evaluation")
        }
        if (mode == ClaimMode.AUTO && !evaluation.autoClaimAllowed) {
            // `DEC-005`: auto-claim needs the rule set and the group to both allow it.
            throw notClaimable("Auto-claim is not allowed for this opportunity")
        }
        if (!providerHealth.isOperational()) {
            throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "INSTANCE_NOT_OPERATIONAL",
                title = "Instance not operational",
                message = "The WhatsApp instance is not operational; the claim was not created",
            )
        }

        val decidedAt = clock.instant()
        // Only one transaction can move the opportunity out of ELIGIBLE.
        opportunityRepository.updateStatus(
            id = opportunity.id,
            expectedVersion = opportunity.version,
            status = OpportunityStatus.CLAIM_PENDING,
            resolutionReason = null,
        ) ?: throw ApiProblemException(
            status = HttpStatus.CONFLICT,
            code = "CONFLICT",
            title = "Opportunity already claimed",
            message = "Another request claimed this opportunity first",
        )

        val claim =
            claimRepository.create(
                ShiftClaimWrite(
                    opportunityId = opportunity.id,
                    mode = mode,
                    // Frozen here so the worker can never resolve a different quote target later.
                    chatId = message.chatId,
                    quotedMessageId = message.providerMessageId,
                    ruleEvaluationId = evaluation.id,
                    decidedAt = decidedAt,
                ),
            ) ?: throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Opportunity already claimed",
                message = "Another request claimed this opportunity first",
            )

        outboxRepository.enqueueSendClaim(claim.id, objectMapper.writeValueAsString(claim.toPayload()))
        return claim.toResponse(emptyList())
    }

    fun list(): ClaimListResponse {
        val claims = claimRepository.findRecent(MAX_LIST_SIZE).map { it.toResponse(emptyList()) }
        return ClaimListResponse(claims = claims, count = claims.size, limit = MAX_LIST_SIZE)
    }

    fun detail(claimId: String): ClaimResponse {
        val claim = loadClaim(claimId)
        return claim.toResponse(attemptRepository.findByClaimId(claim.id))
    }

    /**
     * `EP-026`: re-arms the existing send intent instead of creating a second one, so a manual retry
     * can never turn into a second logical message.
     */
    @Transactional
    fun retry(claimId: String): ClaimResponse {
        val claim = loadClaim(claimId)
        if (claim.status != ClaimStatus.FAILED) {
            throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Claim is not retryable",
                message = "Only a FAILED claim can be retried; this one is ${claim.status}",
            )
        }
        val now = clock.instant()
        claimRepository.transition(
            id = claim.id,
            from = setOf(ClaimStatus.FAILED),
            to = ClaimStatus.RETRY_PENDING,
            at = now,
        ) ?: throw ApiProblemException(
            status = HttpStatus.CONFLICT,
            code = "CONFLICT",
            title = "Claim is not retryable",
            message = "The claim changed state while the retry was being prepared",
        )
        opportunityRepository.findById(claim.opportunityId)?.let { opportunity ->
            opportunityRepository.updateStatus(
                id = opportunity.id,
                expectedVersion = opportunity.version,
                status = OpportunityStatus.CLAIM_PENDING,
                resolutionReason = null,
            )
        }
        outboxRepository.requeue(
            id =
                outboxRepository.findByClaimId(claim.id)?.id
                    ?: throw ApiProblemException(
                        status = HttpStatus.CONFLICT,
                        code = "CONFLICT",
                        title = "Claim has no send intent",
                        message = "This claim has no outbox event to retry",
                    ),
            availableAt = now,
        )
        return loadClaim(claimId).toResponse(attemptRepository.findByClaimId(claim.id))
    }

    /**
     * Compensating action for a claim that should not have been sent. It is deliberately an
     * exception rather than a routine step: WhatsApp leaves a visible "message deleted" mark, and
     * taking a shift back has a social cost inside a small group of colleagues.
     *
     * Deleting is also cosmetic, not transactional. If the person who posted the offer already read
     * the PEGO and assigned the shift, removing the message does not undo that agreement - which is
     * why the reason is recorded and the operator, not a heuristic, decides.
     *
     * Deliberately not one transaction. The provider call happens outside the database, so wrapping
     * the sequence would mean that reporting a refused deletion rolls back the very record of it -
     * the individual writes are transactional, the whole is not, because the whole cannot be.
     */
    fun retract(
        claimId: String,
        request: RetractClaimRequest?,
    ): ClaimResponse {
        val claim = loadClaim(claimId)
        if (claim.status == ClaimStatus.RETRACTED) {
            return claim.toResponse(attemptRepository.findByClaimId(claim.id))
        }
        if (!claim.status.isRetractable()) {
            throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Claim is not retractable",
                message = "Only a CLAIMED claim can be retracted; this one is ${claim.status}",
            )
        }
        val providerMessageId =
            claim.providerMessageId
                ?: throw ApiProblemException(
                    status = HttpStatus.CONFLICT,
                    code = "CONFLICT",
                    title = "Claim has nothing to retract",
                    message = "This claim never reached the provider, so there is no message to delete",
                )

        val now = clock.instant()
        val failureCode =
            runCatching {
                messageRetractor.deleteMessage(
                    DeleteMessage(chatId = claim.chatId, providerMessageId = providerMessageId),
                )
            }.fold(onSuccess = { null }, onFailure = { failure -> failure.retractionCode() })

        val updated =
            claimRepository.recordRetraction(
                id = claim.id,
                at = now,
                reason = request?.reason?.take(64),
                failureCode = failureCode,
            ) ?: throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Claim changed while it was being retracted",
                message = "Reload the claim and retry",
            )
        auditRepository.record(
            AuditEventWrite(
                aggregateType = "SHIFT_CLAIM",
                aggregateId = claim.id,
                eventType = if (failureCode == null) "CLAIM_RETRACTED" else "CLAIM_RETRACTION_FAILED",
                detail = request?.reason?.take(512) ?: failureCode,
                occurredAt = now,
            ),
        )
        if (failureCode != null) {
            // The claim stays CLAIMED: the message is still in the group, and pretending otherwise
            // would be worse than reporting the failure.
            throw ApiProblemException(
                status = HttpStatus.BAD_GATEWAY,
                code = "GREEN_API_UNAVAILABLE",
                title = "Message could not be deleted",
                message = "The provider refused to delete the message; it is still in the group",
            )
        }
        // The opportunity goes back to being explicitly not taken.
        opportunityRepository.findById(claim.opportunityId)?.let { opportunity ->
            opportunityRepository.updateStatus(
                id = opportunity.id,
                expectedVersion = opportunity.version,
                status = OpportunityStatus.REJECTED,
                resolutionReason = "CLAIM_RETRACTED",
            )
        }
        return updated.toResponse(attemptRepository.findByClaimId(claim.id))
    }

    private fun Throwable.retractionCode(): String =
        when (this) {
            is br.com.shiftcatcher.integration.greenapi.GreenApiTransportException -> "GREEN_API_${kind.name}"
            else -> "GREEN_API_UNAVAILABLE"
        }

    private fun notClaimable(detail: String): ApiProblemException =
        ApiProblemException(
            status = HttpStatus.CONFLICT,
            code = "OPPORTUNITY_NOT_CLAIMABLE",
            title = "Opportunity not claimable",
            message = detail,
        )

    private fun loadOpportunity(opportunityId: String): ShiftOpportunity =
        opportunityRepository.findById(parseId(opportunityId, "opportunityId"))
            ?: throw ApiProblemException(
                status = HttpStatus.NOT_FOUND,
                code = "RESOURCE_NOT_FOUND",
                title = "Opportunity not found",
                message = "No shift opportunity matches the supplied identifier",
            )

    private fun loadClaim(claimId: String): ShiftClaim =
        claimRepository.findById(parseId(claimId, "claimId"))
            ?: throw ApiProblemException(
                status = HttpStatus.NOT_FOUND,
                code = "RESOURCE_NOT_FOUND",
                title = "Claim not found",
                message = "No claim matches the supplied identifier",
            )

    private fun parseId(
        raw: String,
        field: String,
    ): UUID =
        runCatching { UUID.fromString(raw) }
            .getOrElse { throw IllegalArgumentException("$field must be a UUID") }

    private fun ShiftClaim.toPayload(): SendClaimPayload =
        SendClaimPayload(claimId = id.toString(), chatId = chatId, quotedMessageId = quotedMessageId, message = message)

    private companion object {
        const val MAX_LIST_SIZE = 100
    }
}

internal fun ShiftClaim.toResponse(attempts: List<ClaimAttempt>): ClaimResponse =
    ClaimResponse(
        id = id.toString(),
        opportunityId = opportunityId.toString(),
        status = status,
        mode = mode,
        chatId = chatId,
        quotedMessageId = quotedMessageId,
        message = message,
        providerMessageId = providerMessageId,
        attemptCount = attemptCount,
        decidedAt = decidedAt,
        claimedAt = claimedAt,
        failedAt = failedAt,
        failureCode = failureCode,
        attempts =
            attempts.map {
                ClaimAttemptResponse(
                    attemptNumber = it.attemptNumber,
                    result = it.result,
                    providerResponseId = it.providerResponseId,
                    failureCode = it.failureCode,
                    latencyMs = it.latencyMs,
                    startedAt = it.startedAt,
                    completedAt = it.completedAt,
                )
            },
    )

data class SendClaimPayload(
    val claimId: String,
    val chatId: String,
    val quotedMessageId: String,
    val message: String,
)

data class ClaimRequest(
    val mode: ClaimMode? = null,
)

data class RetractClaimRequest(
    val reason: String? = null,
)

data class ClaimAttemptResponse(
    val attemptNumber: Int,
    val result: AttemptResult,
    val providerResponseId: String?,
    val failureCode: String?,
    val latencyMs: Int?,
    val startedAt: Instant,
    val completedAt: Instant?,
)

data class ClaimResponse(
    val id: String,
    val opportunityId: String,
    val status: ClaimStatus,
    val mode: ClaimMode,
    val chatId: String,
    val quotedMessageId: String,
    val message: String,
    val providerMessageId: String?,
    val attemptCount: Int,
    val decidedAt: Instant,
    val claimedAt: Instant?,
    val failedAt: Instant?,
    val failureCode: String?,
    val attempts: List<ClaimAttemptResponse>,
)

data class ClaimListResponse(
    val claims: List<ClaimResponse>,
    val count: Int,
    val limit: Int,
)
