package br.com.shiftcatcher.rules

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The configurable hard rules of `04-Domain/Rule-Engine.md`. Every field is optional and a null (or
 * empty list) means the rule is simply not enforced — a rule set that configures nothing rejects
 * nothing. `requiredFields` is the exception that tightens rather than loosens.
 */
data class RuleDefinition(
    /** Global auto-claim switch. Auto-claim also needs the group's own flag (`DEC-005`). */
    val autoClaimEnabled: Boolean = false,
    val minConfidence: BigDecimal? = null,
    val allowedWeekdays: List<DayOfWeek> = emptyList(),
    val earliestStartTime: LocalTime? = null,
    val latestStartTime: LocalTime? = null,
    val maxDurationHours: Int? = null,
    val minAmount: BigDecimal? = null,
    val allowedCities: List<String> = emptyList(),
    val blockedLocations: List<String> = emptyList(),
    val requiredFields: List<String> = emptyList(),
    val maxMessageAgeMinutes: Long? = null,
    val requireOperationalInstance: Boolean = false,
    /**
     * Whether an opportunity the model had to interpret may be claimed automatically. Default is
     * false: the AI widens what we can read, but arming it to send on its own is a separate
     * decision the operator makes once the readings have been seen.
     */
    val allowAutoClaimFromAi: Boolean = false,
) {
    fun validate() {
        minConfidence?.let {
            require(it >= BigDecimal.ZERO && it <= BigDecimal.ONE) { "minConfidence must be between 0 and 1" }
        }
        maxDurationHours?.let { require(it in 1..24) { "maxDurationHours must be between 1 and 24" } }
        minAmount?.let { require(it >= BigDecimal.ZERO) { "minAmount must not be negative" } }
        maxMessageAgeMinutes?.let { require(it > 0) { "maxMessageAgeMinutes must be positive" } }
        requiredFields.forEach {
            require(it in SUPPORTED_REQUIRED_FIELDS) {
                "requiredFields supports only $SUPPORTED_REQUIRED_FIELDS"
            }
        }
        if (earliestStartTime != null && latestStartTime != null) {
            require(!earliestStartTime.isAfter(latestStartTime)) {
                "earliestStartTime must not be after latestStartTime"
            }
        }
    }

    companion object {
        val SUPPORTED_REQUIRED_FIELDS =
            listOf("shiftDate", "startTime", "endTime", "amount", "location", "city", "specialty")
    }
}

enum class RuleSetStatus {
    DRAFT,
    ACTIVE,
    SUPERSEDED,
}

/** `04-Domain/Rule-Engine.md` results. */
enum class EvaluationResult {
    ELIGIBLE,
    REJECTED,
    REVIEW_REQUIRED,
}

/**
 * Reason codes recorded with every evaluation so a decision stays explainable after the rule set
 * that produced it has been superseded.
 */
object RuleReason {
    const val NO_ACTIVE_RULE_SET = "NO_ACTIVE_RULE_SET"
    const val EXTRACTION_AMBIGUOUS = "EXTRACTION_AMBIGUOUS"
    const val REQUIRED_FIELD_MISSING = "REQUIRED_FIELD_MISSING"
    const val CONFIDENCE_BELOW_MINIMUM = "CONFIDENCE_BELOW_MINIMUM"
    const val WEEKDAY_NOT_ALLOWED = "WEEKDAY_NOT_ALLOWED"
    const val START_TIME_OUTSIDE_WINDOW = "START_TIME_OUTSIDE_WINDOW"
    const val DURATION_ABOVE_MAXIMUM = "DURATION_ABOVE_MAXIMUM"
    const val AMOUNT_BELOW_MINIMUM = "AMOUNT_BELOW_MINIMUM"
    const val CITY_NOT_ALLOWED = "CITY_NOT_ALLOWED"
    const val LOCATION_BLOCKED = "LOCATION_BLOCKED"
    const val MESSAGE_TOO_OLD = "MESSAGE_TOO_OLD"
    const val INSTANCE_NOT_OPERATIONAL = "INSTANCE_NOT_OPERATIONAL"
    const val INSTANCE_STATE_UNKNOWN = "INSTANCE_STATE_UNKNOWN"
    const val GROUP_DISABLED = "GROUP_DISABLED"
    const val RULE_EVALUATION_FAILED = "RULE_EVALUATION_FAILED"
    const val AUTO_CLAIM_DISABLED_GLOBALLY = "AUTO_CLAIM_DISABLED_GLOBALLY"
    const val AUTO_CLAIM_DISABLED_FOR_GROUP = "AUTO_CLAIM_DISABLED_FOR_GROUP"
    const val AUTO_CLAIM_DISABLED_FOR_AI_EXTRACTION = "AUTO_CLAIM_DISABLED_FOR_AI_EXTRACTION"
}
