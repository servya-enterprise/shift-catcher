package br.com.shiftcatcher.reliability

import br.com.shiftcatcher.claim.ClaimMode
import br.com.shiftcatcher.claim.ClaimRequest
import br.com.shiftcatcher.claim.ClaimService
import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.observability.AuditEventWrite
import br.com.shiftcatcher.observability.AuditRepository
import br.com.shiftcatcher.shift.ShiftOpportunityRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * The only path that can claim without a human. It is deliberately hard to arm: the application flag
 * here, the active rule set's `autoClaimEnabled`, and the group's own flag must all be on, and the
 * rule engine must already have written `autoClaimAllowed` on the evaluation. On top of that, a
 * provider observation that is missing, stale or non-operational blocks the whole pass — the
 * acceptance criterion "provider health blocks auto".
 */
@Component
class AutoClaimTrigger(
    private val opportunityRepository: ShiftOpportunityRepository,
    private val claimService: ClaimService,
    private val providerHealth: ProviderHealthGate,
    private val auditRepository: AuditRepository,
    private val properties: ShiftCatcherProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun runOnce(): AutoClaimSummary {
        if (!properties.claim.autoClaimEnabled) {
            return AutoClaimSummary(considered = 0, claimed = 0, skippedReason = "AUTO_CLAIM_DISABLED")
        }

        // Work first, health second. Asking the provider on every idle tick burned its rate limit
        // and wrote an audit row a second for nothing; there is no point knowing whether we may act
        // when there is nothing to act on.
        val candidates = opportunityRepository.findAutoClaimable(MAX_PER_PASS)
        if (candidates.isEmpty()) {
            return AutoClaimSummary(considered = 0, claimed = 0, skippedReason = null)
        }

        // There is work, so a stale or failed observation is worth refreshing right now rather than
        // waiting for the next scheduled tick.
        val health = providerHealth.refreshIfDue()
        if (!providerHealth.isFresh(health) || !health.operational) {
            val reason = if (!providerHealth.isFresh(health)) "PROVIDER_STATE_STALE" else "PROVIDER_NOT_OPERATIONAL"
            audit(reason, health.state)
            return AutoClaimSummary(considered = candidates.size, claimed = 0, skippedReason = reason)
        }

        var claimed = 0
        candidates.forEach { opportunity ->
            runCatching { claimService.claim(opportunity.id.toString(), ClaimRequest(mode = ClaimMode.AUTO)) }
                .onSuccess {
                    claimed++
                    auditRepository.record(
                        AuditEventWrite(
                            aggregateType = "SHIFT_OPPORTUNITY",
                            aggregateId = opportunity.id,
                            eventType = "AUTO_CLAIM_DECIDED",
                            detail = "claim ${it.id}",
                            occurredAt = clock.instant(),
                        ),
                    )
                }.onFailure { failure ->
                    // A refused auto-claim is normal (someone claimed first, rules changed); it is
                    // recorded rather than retried in a loop.
                    logger.info("Auto-claim skipped for {}: {}", opportunity.id, failure.message)
                    auditRepository.record(
                        AuditEventWrite(
                            aggregateType = "SHIFT_OPPORTUNITY",
                            aggregateId = opportunity.id,
                            eventType = "AUTO_CLAIM_REFUSED",
                            detail = failure.message?.take(512),
                            occurredAt = clock.instant(),
                        ),
                    )
                }
        }
        return AutoClaimSummary(considered = candidates.size, claimed = claimed, skippedReason = null)
    }

    private fun audit(
        reason: String,
        state: String?,
    ) {
        auditRepository.record(
            AuditEventWrite(
                aggregateType = "PROVIDER",
                aggregateId = null,
                eventType = "AUTO_CLAIM_BLOCKED",
                detail = "$reason${state?.let { " state=$it" } ?: ""}",
                occurredAt = clock.instant(),
            ),
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AutoClaimTrigger::class.java)
        const val MAX_PER_PASS = 20
    }
}

data class AutoClaimSummary(
    val considered: Int,
    val claimed: Int,
    val skippedReason: String?,
)

@Component
@ConditionalOnProperty(
    prefix = "shift-catcher.claim",
    name = ["worker-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class AutoClaimScheduler(
    private val trigger: AutoClaimTrigger,
) {
    @Scheduled(fixedDelayString = "\${shift-catcher.claim.auto-claim-interval-ms:5000}")
    fun run() {
        trigger.runOnce()
    }
}
