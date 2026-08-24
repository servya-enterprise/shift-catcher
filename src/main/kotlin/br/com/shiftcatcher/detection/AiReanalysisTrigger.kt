package br.com.shiftcatcher.detection

import br.com.shiftcatcher.ai.AiShiftParserPort
import br.com.shiftcatcher.messaging.IncomingMessageRepository
import br.com.shiftcatcher.shift.ExtractionMethod
import br.com.shiftcatcher.shift.OpportunityStatus
import br.com.shiftcatcher.shift.ShiftOpportunityRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Second chance for what the deterministic parser could not read. It runs well behind the fast path:
 * a complete offer is already claimed within a couple of seconds and never reaches here, so the
 * seconds an inference costs are spent only on messages that would otherwise sit in manual review.
 *
 * It only ever revisits an opportunity the parser itself left ambiguous, and only once — an
 * `AI_FALLBACK` extraction is not fed back into the model.
 */
@Component
@ConditionalOnProperty(prefix = "shift-catcher.ai", name = ["enabled"], havingValue = "true")
class AiReanalysisTrigger(
    private val opportunityRepository: ShiftOpportunityRepository,
    private val messageRepository: IncomingMessageRepository,
    private val analysisService: MessageAnalysisService,
    private val aiParser: AiShiftParserPort,
) {
    fun runOnce(): Int {
        if (!aiParser.isEnabled()) {
            return 0
        }
        val pending =
            opportunityRepository
                .findRecent(MAX_SCAN)
                .filter { it.status == OpportunityStatus.REVIEW_REQUIRED }
                .filter { it.ambiguousFields.isNotEmpty() }
                .filter { it.extractionMethod == ExtractionMethod.DETERMINISTIC }
                .take(MAX_PER_PASS)

        var resolved = 0
        pending.forEach { opportunity ->
            val message = messageRepository.findById(opportunity.sourceMessageId) ?: return@forEach
            runCatching {
                analysisService.analyze(
                    AnalyzeMessageCommand(
                        messageId = message.id,
                        groupId = opportunity.groupId,
                        text = message.text,
                        messageTimestamp = message.providerTimestamp,
                        // The whole point of this pass: the webhook path may not call a model, this may.
                        allowAiFallback = true,
                    ),
                )
            }.onSuccess { outcome ->
                if (outcome.aiInvoked) resolved++
            }.onFailure { failure ->
                logger.warn("AI reanalysis failed for {}", opportunity.id, failure)
            }
        }
        return resolved
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AiReanalysisTrigger::class.java)
        const val MAX_SCAN = 100
        const val MAX_PER_PASS = 3
    }
}

@Component
@ConditionalOnProperty(prefix = "shift-catcher.ai", name = ["enabled"], havingValue = "true")
class AiReanalysisScheduler(
    private val trigger: AiReanalysisTrigger,
) {
    @Scheduled(fixedDelayString = "\${shift-catcher.ai.reanalysis-interval-ms:5000}")
    fun run() {
        trigger.runOnce()
    }
}
