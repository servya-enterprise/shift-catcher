package br.com.shiftcatcher.reliability

import br.com.shiftcatcher.claim.AttemptResult
import br.com.shiftcatcher.claim.ClaimAttemptRepository
import br.com.shiftcatcher.claim.ClaimAttemptWrite
import br.com.shiftcatcher.claim.ClaimStatus
import br.com.shiftcatcher.claim.SendClaimPayload
import br.com.shiftcatcher.claim.ShiftClaimRepository
import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.integration.greenapi.GreenApiFailureKind
import br.com.shiftcatcher.integration.greenapi.GreenApiTransportException
import br.com.shiftcatcher.integration.greenapi.SendQuotedMessage
import br.com.shiftcatcher.integration.greenapi.WhatsAppMessageSender
import br.com.shiftcatcher.shift.OpportunityStatus
import br.com.shiftcatcher.shift.ShiftOpportunityRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The worker of `04-Domain/Claim-Engine.md`: it is the only place that sends `PEGO`. It never
 * re-derives what to send — chat and quote were frozen when the claim was decided — and it retries
 * only transient failures, on the short budget from
 * `02-Architecture/Transactionality-and-Idempotency.md`.
 */
@Component
class ClaimOutboxProcessor(
    private val outboxRepository: OutboxRepository,
    private val claimRepository: ShiftClaimRepository,
    private val attemptRepository: ClaimAttemptRepository,
    private val opportunityRepository: ShiftOpportunityRepository,
    private val messageSender: WhatsAppMessageSender,
    private val properties: ShiftCatcherProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Drains everything currently due. Returns how many events were processed. */
    fun processDueEvents(): Int {
        var processed = 0
        while (true) {
            val now = clock.instant()
            val event =
                outboxRepository.leaseNext(
                    now = now,
                    leaseUntil = now.plusSeconds(properties.claim.leaseSeconds),
                ) ?: return processed
            runCatching { process(event) }
                .onFailure { failure ->
                    logger.error("Outbox event {} could not be processed", event.id, failure)
                    outboxRepository.markFailed(event.id, failure.message ?: "unexpected failure", clock.instant())
                }
            processed++
        }
    }

    private fun process(event: OutboxEvent) {
        val payload = objectMapper.readValue(event.payloadJson, SendClaimPayload::class.java)
        val claim =
            claimRepository.findById(event.aggregateId)
                ?: error("Claim ${event.aggregateId} referenced by outbox event ${event.id} is missing")
        if (claim.status == ClaimStatus.CLAIMED) {
            // Already delivered; the intent is simply stale.
            outboxRepository.markDone(event.id, clock.instant())
            return
        }
        claimRepository.transition(
            id = claim.id,
            from = setOf(ClaimStatus.CREATED, ClaimStatus.RETRY_PENDING, ClaimStatus.SENDING),
            to = ClaimStatus.SENDING,
            at = clock.instant(),
        ) ?: run {
            outboxRepository.markFailed(event.id, "claim is ${claim.status}", clock.instant())
            return
        }

        val delays = properties.claim.retryDelaysMs.ifEmpty { listOf(0L) }
        var lastFailure: String? = null
        delays.forEachIndexed { index, delayMs ->
            if (delayMs > 0) {
                Thread.sleep(delayMs)
            }
            val attemptNumber = claimRepository.incrementAttemptCount(claim.id)
            val startedAt = clock.instant()
            val outcome =
                runCatching {
                    messageSender.sendQuotedMessage(
                        SendQuotedMessage(
                            chatId = payload.chatId,
                            message = payload.message,
                            quotedMessageId = payload.quotedMessageId,
                        ),
                    )
                }
            val completedAt = clock.instant()
            val latencyMs = Duration.between(startedAt, completedAt).toMillis().toInt()

            outcome.onSuccess { receipt ->
                attemptRepository.record(
                    ClaimAttemptWrite(
                        claimId = claim.id,
                        attemptNumber = attemptNumber,
                        startedAt = startedAt,
                        completedAt = completedAt,
                        providerResponseId = receipt.providerMessageId,
                        result = AttemptResult.ACCEPTED,
                        failureCode = null,
                        latencyMs = latencyMs,
                    ),
                )
                succeed(claim.id, claim.opportunityId, receipt.providerMessageId, event, completedAt)
                return
            }
            val failure = outcome.exceptionOrNull()!!
            val transient = failure.isTransient()
            lastFailure = failure.failureCode()
            attemptRepository.record(
                ClaimAttemptWrite(
                    claimId = claim.id,
                    attemptNumber = attemptNumber,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    providerResponseId = null,
                    result = if (transient) AttemptResult.TRANSIENT_FAILURE else AttemptResult.PERMANENT_FAILURE,
                    failureCode = lastFailure,
                    latencyMs = latencyMs,
                ),
            )
            if (!transient) {
                // Retrying a rejected request would only repeat the rejection.
                fail(claim.id, claim.opportunityId, lastFailure, event, completedAt)
                return
            }
            if (index == delays.lastIndex) {
                fail(claim.id, claim.opportunityId, lastFailure, event, completedAt)
                return
            }
        }
    }

    private fun succeed(
        claimId: java.util.UUID,
        opportunityId: java.util.UUID,
        providerMessageId: String,
        event: OutboxEvent,
        at: Instant,
    ) {
        claimRepository.transition(
            id = claimId,
            from = setOf(ClaimStatus.SENDING),
            to = ClaimStatus.PROVIDER_ACCEPTED,
            providerMessageId = providerMessageId,
            at = at,
        )
        claimRepository.transition(
            id = claimId,
            from = setOf(ClaimStatus.PROVIDER_ACCEPTED),
            to = ClaimStatus.CLAIMED,
            at = at,
        )
        opportunityRepository.findById(opportunityId)?.let { opportunity ->
            opportunityRepository.updateStatus(
                id = opportunity.id,
                expectedVersion = opportunity.version,
                status = OpportunityStatus.CLAIMED,
                resolutionReason = null,
            )
        }
        outboxRepository.markDone(event.id, at)
    }

    private fun fail(
        claimId: java.util.UUID,
        opportunityId: java.util.UUID,
        failureCode: String?,
        event: OutboxEvent,
        at: Instant,
    ) {
        claimRepository.transition(
            id = claimId,
            from = setOf(ClaimStatus.SENDING),
            to = ClaimStatus.FAILED,
            failureCode = failureCode,
            at = at,
        )
        opportunityRepository.findById(opportunityId)?.let { opportunity ->
            opportunityRepository.updateStatus(
                id = opportunity.id,
                expectedVersion = opportunity.version,
                status = OpportunityStatus.CLAIM_FAILED,
                resolutionReason = failureCode,
            )
        }
        outboxRepository.markFailed(event.id, failureCode ?: "send failed", at)
    }

    private fun Throwable.isTransient(): Boolean =
        this is GreenApiTransportException &&
            kind in setOf(GreenApiFailureKind.TIMEOUT, GreenApiFailureKind.SERVER_ERROR)

    private fun Throwable.failureCode(): String =
        when {
            this is GreenApiTransportException -> "GREEN_API_${kind.name}"
            else -> "GREEN_API_UNAVAILABLE"
        }

    private companion object {
        val logger = LoggerFactory.getLogger(ClaimOutboxProcessor::class.java)
    }
}
