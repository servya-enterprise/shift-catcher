package br.com.shiftcatcher.reliability

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drives the outbox on a short interval. Kept separate from the processor so tests can drain the
 * outbox deterministically instead of racing a background thread.
 */
@Component
@ConditionalOnProperty(
    prefix = "shift-catcher.claim",
    name = ["worker-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ClaimOutboxScheduler(
    private val processor: ClaimOutboxProcessor,
) {
    @Scheduled(fixedDelayString = "\${shift-catcher.claim.poll-interval-ms:1000}")
    fun drain() {
        processor.processDueEvents()
    }
}
