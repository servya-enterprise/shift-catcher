package br.com.shiftcatcher.rules

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.shift.ShiftOpportunityRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Closes the gap between detection and eligibility. Ingestion parks an opportunity in `EVALUATING`
 * because `03-Integrations/Webhook-Contract.md` forbids running rules inside the webhook request;
 * this is the out-of-band pass that contract implies.
 *
 * It only ever computes a verdict — it sends nothing. An opportunity it promotes to `ELIGIBLE` still
 * needs every auto-claim switch before anything leaves for WhatsApp.
 */
@Component
class AutoEvaluationTrigger(
    private val opportunityRepository: ShiftOpportunityRepository,
    private val evaluationService: OpportunityEvaluationService,
    private val properties: ShiftCatcherProperties,
) {
    fun runOnce(): Int {
        if (!properties.claim.autoEvaluateEnabled) {
            return 0
        }
        val pending = opportunityRepository.findAwaitingEvaluation(MAX_PER_PASS)
        var evaluated = 0
        pending.forEach { opportunity ->
            runCatching { evaluationService.reevaluate(opportunity.id.toString()) }
                .onSuccess { evaluated++ }
                .onFailure { failure ->
                    // A refusal here is normal (claimed meanwhile, ignored meanwhile); the next pass
                    // simply will not see it any more.
                    logger.info("Auto-evaluation skipped for {}: {}", opportunity.id, failure.message)
                }
        }
        return evaluated
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AutoEvaluationTrigger::class.java)
        const val MAX_PER_PASS = 20
    }
}

@Component
@ConditionalOnProperty(
    prefix = "shift-catcher.claim",
    name = ["worker-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class AutoEvaluationScheduler(
    private val trigger: AutoEvaluationTrigger,
) {
    @Scheduled(fixedDelayString = "\${shift-catcher.claim.auto-evaluate-interval-ms:3000}")
    fun run() {
        trigger.runOnce()
    }
}
