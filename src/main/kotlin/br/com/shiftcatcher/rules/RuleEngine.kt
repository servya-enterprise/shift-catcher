package br.com.shiftcatcher.rules

import br.com.shiftcatcher.availability.Commitment
import br.com.shiftcatcher.shift.ExtractionMethod
import br.com.shiftcatcher.shift.ShiftOpportunity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure evaluation of the hard rules. It reads only what is already persisted about an opportunity,
 * which is what keeps the AI from buying itself a shortcut: an `AI_FALLBACK` extraction is judged by
 * exactly the same rules, and its self-reported confidence is subject to `minConfidence`.
 *
 * The split between the two negative outcomes is deliberate:
 * - `REJECTED` means the offer is definitively not what the operator asked for.
 * - `REVIEW_REQUIRED` means we are not certain enough to say, which is the fail-safe of `DEC-005`.
 */
@Component
class RuleEngine {
    fun evaluate(context: EvaluationContext): RuleOutcome =
        runCatching { evaluateOrThrow(context) }
            .getOrElse { failure ->
                // `04-Domain/Rule-Engine.md`: an error evaluating a hard rule is never an approval.
                logger.warn("Rule evaluation failed; failing safe to review", failure)
                RuleOutcome(
                    result = EvaluationResult.REVIEW_REQUIRED,
                    reasons = listOf(RuleReason.RULE_EVALUATION_FAILED),
                    autoClaimAllowed = false,
                )
            }

    private fun evaluateOrThrow(context: EvaluationContext): RuleOutcome {
        val opportunity = context.opportunity
        val definition = context.definition
        val review = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        if (opportunity.ambiguousFields.isNotEmpty()) {
            review += RuleReason.EXTRACTION_AMBIGUOUS
        }
        if (definition.requiredFields.any { !opportunity.hasField(it) }) {
            review += RuleReason.REQUIRED_FIELD_MISSING
        }
        definition.minConfidence?.let { minimum ->
            // Low confidence says we are unsure, not that the offer is unwanted.
            if ((opportunity.confidence ?: BigDecimal.ZERO) < minimum) {
                review += RuleReason.CONFIDENCE_BELOW_MINIMUM
            }
        }

        if (!context.groupEnabled) {
            rejected += RuleReason.GROUP_DISABLED
        }
        if (definition.allowedWeekdays.isNotEmpty()) {
            val weekday = opportunity.shiftDate?.dayOfWeek
            when {
                weekday == null -> review += RuleReason.REQUIRED_FIELD_MISSING
                weekday !in definition.allowedWeekdays -> rejected += RuleReason.WEEKDAY_NOT_ALLOWED
            }
        }
        val start = opportunity.startTime
        if (definition.earliestStartTime != null || definition.latestStartTime != null) {
            when {
                start == null -> {
                    review += RuleReason.REQUIRED_FIELD_MISSING
                }

                outsideWindow(start, definition.earliestStartTime, definition.latestStartTime) -> {
                    rejected += RuleReason.START_TIME_OUTSIDE_WINDOW
                }
            }
        }
        definition.maxDurationHours?.let { maximum ->
            val duration = opportunity.durationHours()
            when {
                duration == null -> review += RuleReason.REQUIRED_FIELD_MISSING
                duration > maximum -> rejected += RuleReason.DURATION_ABOVE_MAXIMUM
            }
        }
        definition.minAmount?.let { minimum ->
            val amount = opportunity.amount
            when {
                amount == null -> review += RuleReason.REQUIRED_FIELD_MISSING
                amount < minimum -> rejected += RuleReason.AMOUNT_BELOW_MINIMUM
            }
        }
        if (definition.allowedCities.isNotEmpty()) {
            val city = opportunity.city
            when {
                city == null -> {
                    review += RuleReason.REQUIRED_FIELD_MISSING
                }

                definition.allowedCities.none { it.equals(city, ignoreCase = true) } -> {
                    rejected += RuleReason.CITY_NOT_ALLOWED
                }
            }
        }
        if (definition.blockedLocations.isNotEmpty() && opportunity.location != null) {
            if (definition.blockedLocations.any { it.equals(opportunity.location, ignoreCase = true) }) {
                rejected += RuleReason.LOCATION_BLOCKED
            }
        }
        definition.agendaConflictPolicy?.let { policy ->
            when (agendaVerdict(opportunity, definition.agendaConflictMode, context.commitments)) {
                AgendaVerdict.CLEAR -> {
                    Unit
                }

                // No date means the rule cannot run at all, which is the same missing-field case
                // every other configured rule reports.
                AgendaVerdict.DATE_UNKNOWN -> {
                    review += RuleReason.REQUIRED_FIELD_MISSING
                }

                // Something is there but the hours are unreadable on one side. Not knowing whether
                // they cross is not the same as knowing they do not.
                AgendaVerdict.UNCERTAIN -> {
                    review += RuleReason.AGENDA_CONFLICT_UNCERTAIN
                }

                AgendaVerdict.CONFLICT -> {
                    when (policy) {
                        // A collision is a fact about her diary, not a doubt about the offer.
                        AgendaConflictPolicy.REJECT -> rejected += RuleReason.AGENDA_CONFLICT

                        AgendaConflictPolicy.REVIEW -> review += RuleReason.AGENDA_CONFLICT
                    }
                }
            }
        }
        definition.maxMessageAgeMinutes?.let { maximum ->
            val age = Duration.between(context.messageTimestamp, context.now).toMinutes()
            if (age > maximum) {
                // A stale offer is gone, not uncertain.
                rejected += RuleReason.MESSAGE_TOO_OLD
            }
        }
        if (definition.requireOperationalInstance) {
            when (context.instanceOperational) {
                // Transient provider trouble must not discard a good offer.
                false -> review += RuleReason.INSTANCE_NOT_OPERATIONAL

                // Null means the caller did not observe the provider at this stage. Evaluation judges
                // the offer, not the infrastructure: parking an opportunity in review because the
                // network blinked would demand a human for something that fixes itself. The claim
                // engine enforces the live precondition, and an offer left ELIGIBLE is claimed as
                // soon as the provider is back.
                null -> Unit

                true -> Unit
            }
        }

        val result =
            when {
                rejected.isNotEmpty() -> EvaluationResult.REJECTED
                review.isNotEmpty() -> EvaluationResult.REVIEW_REQUIRED
                else -> EvaluationResult.ELIGIBLE
            }
        val autoClaimReasons = mutableListOf<String>()
        if (!definition.autoClaimEnabled) {
            autoClaimReasons += RuleReason.AUTO_CLAIM_DISABLED_GLOBALLY
        }
        if (!context.groupAutoClaimEnabled) {
            autoClaimReasons += RuleReason.AUTO_CLAIM_DISABLED_FOR_GROUP
        }
        // An AI reading stays eligible for a one-click manual claim, but sending on its own is a
        // separate permission: a hallucination here would be a real message in a real group.
        if (opportunity.extractionMethod == ExtractionMethod.AI_FALLBACK && !definition.allowAutoClaimFromAi) {
            autoClaimReasons += RuleReason.AUTO_CLAIM_DISABLED_FOR_AI_EXTRACTION
        }
        return RuleOutcome(
            result = result,
            reasons = (rejected + review + autoClaimReasons).distinct(),
            // Auto-claim needs an eligible offer plus both switches; `DEC-005` keeps it opt-in.
            autoClaimAllowed = result == EvaluationResult.ELIGIBLE && autoClaimReasons.isEmpty(),
        )
    }

    private fun agendaVerdict(
        opportunity: ShiftOpportunity,
        mode: AgendaConflictMode,
        commitments: List<Commitment>,
    ): AgendaVerdict {
        val date = opportunity.shiftDate ?: return AgendaVerdict.DATE_UNKNOWN
        if (commitments.isEmpty()) {
            return AgendaVerdict.CLEAR
        }
        if (mode == AgendaConflictMode.SAME_DAY) {
            // Only the date matters here, so an unknown start hour costs nothing.
            return if (commitments.any { it.shiftDate == date }) AgendaVerdict.CONFLICT else AgendaVerdict.CLEAR
        }

        val offered = span(date, opportunity.startTime, opportunity.endTime, opportunity.endsNextDay)
        var uncertain = false
        commitments.forEach { commitment ->
            val held = span(commitment.shiftDate, commitment.startTime, commitment.endTime, commitment.endsNextDay)
            when {
                offered != null && held != null -> if (offered overlaps held) return AgendaVerdict.CONFLICT

                // Windows cannot be compared. Only a commitment that shares the date is worth
                // stopping for: the neighbouring days were read to catch a crossing, and without
                // hours there is no crossing to catch.
                commitment.shiftDate == date -> uncertain = true

                else -> Unit
            }
        }
        return if (uncertain) AgendaVerdict.UNCERTAIN else AgendaVerdict.CLEAR
    }

    /**
     * Places a shift on the calendar as an absolute interval. A window that does not end after it
     * starts has run past midnight — the same reading `durationHours` uses — so the end lands on the
     * following day.
     */
    private fun span(
        date: LocalDate,
        start: LocalTime?,
        end: LocalTime?,
        endsNextDay: Boolean,
    ): Span? {
        if (start == null || end == null) {
            return null
        }
        val from = date.atTime(start)
        val spillover = endsNextDay || !end.isAfter(start)
        return Span(from, (if (spillover) date.plusDays(1) else date).atTime(end))
    }

    private fun outsideWindow(
        start: LocalTime,
        earliest: LocalTime?,
        latest: LocalTime?,
    ): Boolean = (earliest != null && start < earliest) || (latest != null && start > latest)

    private fun ShiftOpportunity.hasField(field: String): Boolean =
        when (field) {
            "shiftDate" -> shiftDate != null
            "startTime" -> startTime != null
            "endTime" -> endTime != null
            "amount" -> amount != null
            "location" -> location != null
            "city" -> city != null
            "specialty" -> specialty != null
            else -> throw IllegalArgumentException("Unsupported required field: $field")
        }

    private fun ShiftOpportunity.durationHours(): Long? {
        val from = startTime ?: return null
        val to = endTime ?: return null
        val minutes = Duration.between(from, to).toMinutes()
        val spanned = if (minutes <= 0) minutes + Duration.ofDays(1).toMinutes() else minutes
        return spanned / 60
    }

    private companion object {
        val logger = LoggerFactory.getLogger(RuleEngine::class.java)
    }
}

data class EvaluationContext(
    val opportunity: ShiftOpportunity,
    val definition: RuleDefinition,
    val groupEnabled: Boolean,
    val groupAutoClaimEnabled: Boolean,
    val messageTimestamp: Instant,
    val instanceOperational: Boolean?,
    val now: Instant,
    val timezone: ZoneId,
    /**
     * What she is already committed to around the offered date, supplied by the `availability`
     * port. The engine stays pure: it is handed the diary, it does not go and read it.
     */
    val commitments: List<Commitment> = emptyList(),
)

/** Half-open: a shift that ends exactly when the next one begins is not a collision. */
private data class Span(
    val from: LocalDateTime,
    val to: LocalDateTime,
) {
    infix fun overlaps(other: Span): Boolean = from < other.to && other.from < to
}

private enum class AgendaVerdict {
    CLEAR,
    CONFLICT,
    UNCERTAIN,
    DATE_UNKNOWN,
}

data class RuleOutcome(
    val result: EvaluationResult,
    val reasons: List<String>,
    val autoClaimAllowed: Boolean,
)
