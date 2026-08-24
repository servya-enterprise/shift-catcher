package br.com.shiftcatcher.detection

import br.com.shiftcatcher.ai.AiParseRequest
import br.com.shiftcatcher.ai.AiParseResult
import br.com.shiftcatcher.ai.AiShiftParserPort
import br.com.shiftcatcher.extraction.ExtractedShift
import br.com.shiftcatcher.extraction.ShiftExtractor
import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.shift.ExtractionMethod
import br.com.shiftcatcher.shift.OpportunityStatus
import br.com.shiftcatcher.shift.ShiftOpportunity
import br.com.shiftcatcher.shift.ShiftOpportunityRepository
import br.com.shiftcatcher.shift.ShiftOpportunityWrite
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Runs the three detection/extraction stages in order and records the outcome. The pipeline never
 * decides to claim anything: the furthest it moves an opportunity is `EVALUATING`, which is where
 * the rule engine of `WP-POC-005` will pick it up, or `REVIEW_REQUIRED` when anything essential is
 * unresolved (`DEC-005` fail-safe).
 */
@Service
class MessageAnalysisService(
    private val detector: MessageDetector,
    private val extractor: ShiftExtractor,
    private val aiParser: AiShiftParserPort,
    private val detectionResultRepository: DetectionResultRepository,
    private val opportunityRepository: ShiftOpportunityRepository,
    private val properties: ShiftCatcherProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun analyze(command: AnalyzeMessageCommand): AnalysisOutcome {
        val existing = opportunityRepository.findBySourceMessageId(command.messageId)
        if (existing != null && !existing.status.isOpenForAnalysis()) {
            // A reviewed, ignored or claimed opportunity is a decision already taken; re-running the
            // parser must not quietly discard it.
            return AnalysisOutcome(
                candidate = true,
                detection = detectionResultRepository.findByMessageId(command.messageId)?.toOutcome(),
                opportunity = existing,
                aiInvoked = false,
                skippedBecauseDecided = true,
            )
        }

        val startedAt = clock.instant()
        val detection = detector.detect(command.text)
        detectionResultRepository.upsert(
            DetectionResultWrite(
                messageId = command.messageId,
                candidate = detection.candidate,
                score = detection.score,
                signals = detection.signals.map { it.name },
                detectionStartedAt = startedAt,
                completedAt = clock.instant(),
            ),
        )
        if (!detection.candidate) {
            return AnalysisOutcome(
                candidate = false,
                detection = detection,
                opportunity = null,
                aiInvoked = false,
                skippedBecauseDecided = false,
            )
        }

        val resolved = resolve(command)
        val opportunity =
            opportunityRepository.upsert(
                ShiftOpportunityWrite(
                    sourceMessageId = command.messageId,
                    groupId = command.groupId,
                    status =
                        if (resolved.ambiguousFields.isEmpty()) {
                            OpportunityStatus.EVALUATING
                        } else {
                            OpportunityStatus.REVIEW_REQUIRED
                        },
                    shiftDate = resolved.shiftDate,
                    startTime = resolved.startTime,
                    endTime = resolved.endTime,
                    endsNextDay = resolved.endsNextDay,
                    location = resolved.location,
                    city = resolved.city,
                    amount = resolved.amount,
                    currency = resolved.currency,
                    specialty = resolved.specialty,
                    notes = resolved.notes,
                    extractionMethod = resolved.method,
                    confidence = resolved.confidence,
                    ambiguousFields = resolved.ambiguousFields,
                    resolutionReason = resolved.reason,
                    detectedAt = startedAt,
                    extractionCompletedAt = clock.instant(),
                ),
            )
        return AnalysisOutcome(
            candidate = true,
            detection = detection,
            opportunity = opportunity,
            aiInvoked = resolved.aiInvoked,
            skippedBecauseDecided = false,
        )
    }

    /** EP-034 sandbox: same pipeline, nothing persisted. */
    fun preview(
        text: String,
        messageTimestamp: Instant,
    ): AnalysisPreview {
        val detection = detector.detect(text)
        if (!detection.candidate) {
            return AnalysisPreview(detection = detection, extraction = null, resolved = null)
        }
        val resolved =
            resolve(
                AnalyzeMessageCommand(
                    messageId = PREVIEW_MESSAGE_ID,
                    groupId = null,
                    text = text,
                    messageTimestamp = messageTimestamp,
                    allowAiFallback = true,
                ),
            )
        return AnalysisPreview(
            detection = detection,
            extraction = extractor.extract(text, messageTimestamp),
            resolved = resolved,
        )
    }

    private fun resolve(command: AnalyzeMessageCommand): ResolvedShift {
        val deterministic = extractor.extract(command.text, command.messageTimestamp)
        val base =
            ResolvedShift(
                shiftDate = deterministic.shiftDate,
                startTime = deterministic.startTime,
                endTime = deterministic.endTime,
                endsNextDay = deterministic.endsNextDay,
                location = deterministic.location,
                city = deterministic.city,
                amount = deterministic.amount,
                currency = deterministic.currency,
                specialty = deterministic.specialty,
                notes = deterministic.notes,
                confidence = deterministic.confidence,
                ambiguousFields = deterministic.ambiguousFields,
                method = ExtractionMethod.DETERMINISTIC,
                reason = if (deterministic.ambiguousFields.isEmpty()) null else ESSENTIAL_FIELD_AMBIGUOUS,
                aiInvoked = false,
            )

        // `07-AI/Fallback-Policy.md`: only a candidate, only when something relevant is still
        // ambiguous, only when an adapter is enabled, and never inside the webhook request path.
        if (base.ambiguousFields.isEmpty() || !command.allowAiFallback || !aiParser.isEnabled()) {
            return base
        }

        val aiResult =
            runCatching {
                aiParser.parse(
                    AiParseRequest(
                        text = command.text,
                        messageTimestamp = command.messageTimestamp,
                        timezone = properties.detection.timezone,
                        knownLocations = properties.detection.knownLocations,
                    ),
                )
            }.getOrElse { failure ->
                logger.warn("AI shift parser failed; keeping the deterministic result", failure)
                return base.copy(reason = AI_UNAVAILABLE, aiInvoked = true)
            }

        if (!aiResult.isSchemaValid()) {
            return base.copy(reason = AI_RESPONSE_INVALID, aiInvoked = true)
        }
        if (!aiResult.isShiftOffer) {
            // The model interprets, it does not decide: a "not an offer" answer sends the message to
            // a human instead of terminating it.
            return base.copy(reason = AI_NOT_A_SHIFT_OFFER, aiInvoked = true)
        }
        return merge(base, aiResult, deterministic)
    }

    /** Deterministic values win wherever they exist; the model may only fill the gaps it was called for. */
    private fun merge(
        base: ResolvedShift,
        aiResult: AiParseResult,
        deterministic: ExtractedShift,
    ): ResolvedShift {
        val shiftDate = deterministic.shiftDate ?: aiResult.date
        val startTime = deterministic.startTime ?: aiResult.startTime
        val endTime =
            deterministic.endTime
                ?: aiResult.endTime
                ?: durationEnd(startTime, aiResult.durationHours ?: deterministic.durationHours)
        val ambiguous = mutableListOf<String>()
        if (shiftDate == null) ambiguous += "shiftDate"
        if (startTime == null) ambiguous += "startTime"
        if (endTime == null) ambiguous += "endTime"
        ambiguous += aiResult.ambiguousFields.filter { it !in ambiguous }

        val confidence =
            listOfNotNull(confidenceFor(ambiguous.size), aiResult.confidence).minOrNull()
                ?: BigDecimal.ZERO
        return base.copy(
            shiftDate = shiftDate,
            startTime = startTime,
            endTime = endTime,
            endsNextDay = if (startTime != null && endTime != null) !endTime.isAfter(startTime) else base.endsNextDay,
            location = deterministic.location ?: aiResult.location,
            city = deterministic.city ?: aiResult.city,
            amount = deterministic.amount ?: aiResult.amount,
            currency = deterministic.currency ?: aiResult.currency,
            specialty = deterministic.specialty ?: aiResult.specialty,
            notes = deterministic.notes ?: aiResult.notes,
            confidence = confidence,
            ambiguousFields = ambiguous.toList(),
            method = ExtractionMethod.AI_FALLBACK,
            reason = if (ambiguous.isEmpty()) null else ESSENTIAL_FIELD_AMBIGUOUS,
            aiInvoked = true,
        )
    }

    private fun durationEnd(
        start: LocalTime?,
        durationHours: Int?,
    ): LocalTime? {
        if (start == null || durationHours == null) return null
        return start.plusHours(durationHours.toLong())
    }

    private fun confidenceFor(ambiguousCount: Int): BigDecimal =
        BigDecimal.ONE
            .subtract(BigDecimal("0.25").multiply(BigDecimal(ambiguousCount)))
            .max(BigDecimal.ZERO)
            .setScale(4, java.math.RoundingMode.HALF_UP)

    private fun DetectionResultRecord.toOutcome(): DetectionOutcome =
        DetectionOutcome(
            candidate = candidate,
            score = score,
            signals = signals.mapNotNull { name -> runCatching { DetectionSignal.valueOf(name) }.getOrNull() },
        )

    private companion object {
        val logger = LoggerFactory.getLogger(MessageAnalysisService::class.java)
        val PREVIEW_MESSAGE_ID: UUID = UUID(0, 0)
        const val ESSENTIAL_FIELD_AMBIGUOUS = "ESSENTIAL_FIELD_AMBIGUOUS"
        const val AI_RESPONSE_INVALID = "AI_RESPONSE_INVALID"
        const val AI_NOT_A_SHIFT_OFFER = "AI_NOT_A_SHIFT_OFFER"
        const val AI_UNAVAILABLE = "AI_UNAVAILABLE"
    }
}

data class AnalyzeMessageCommand(
    val messageId: UUID,
    val groupId: UUID?,
    val text: String,
    val messageTimestamp: Instant,
    /** False on the webhook path: `03-Integrations/Webhook-Contract.md` forbids AI inside the request. */
    val allowAiFallback: Boolean,
)

data class ResolvedShift(
    val shiftDate: LocalDate?,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val endsNextDay: Boolean,
    val location: String?,
    val city: String?,
    val amount: BigDecimal?,
    val currency: String?,
    val specialty: String?,
    val notes: String?,
    val confidence: BigDecimal,
    val ambiguousFields: List<String>,
    val method: ExtractionMethod,
    val reason: String?,
    val aiInvoked: Boolean,
)

data class AnalysisOutcome(
    val candidate: Boolean,
    val detection: DetectionOutcome?,
    val opportunity: ShiftOpportunity?,
    val aiInvoked: Boolean,
    val skippedBecauseDecided: Boolean,
)

data class AnalysisPreview(
    val detection: DetectionOutcome,
    val extraction: ExtractedShift?,
    val resolved: ResolvedShift?,
)
