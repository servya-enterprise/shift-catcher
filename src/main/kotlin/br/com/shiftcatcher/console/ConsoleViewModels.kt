package br.com.shiftcatcher.console

import br.com.shiftcatcher.availability.CommitmentResponse
import br.com.shiftcatcher.claim.ClaimResponse
import br.com.shiftcatcher.messaging.IncomingMessageResponse
import br.com.shiftcatcher.shift.ShiftOpportunityResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything the templates read is shaped here, already formatted.
 *
 * Two reasons. Times become strings in *her* timezone once, in Kotlin, instead of being converted
 * in a dozen template expressions. And a template that only reads strings and booleans cannot
 * quietly start calling into a service.
 */
class ConsoleFormatter(
    private val zone: ZoneId,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun moment(instant: Instant?): String = instant?.let { MOMENT.format(it.atZone(zone)) } ?: EMPTY

    /**
     * The line above the window: "Hoje", "Amanhã", or "qua, 27/08".
     *
     * Relative wording only for the two days she can act on. Beyond that a weekday is what tells
     * her whether a shift collides with something, and "em 3 dias" does not.
     */
    fun dateEyebrow(date: LocalDate?): String {
        if (date == null) return "sem data"
        val today = LocalDate.ofInstant(clock.instant(), zone)
        return when (date) {
            today -> "Hoje"
            today.plusDays(1) -> "Amanhã"
            today.minusDays(1) -> "Ontem"
            else -> "${WEEKDAY.format(date)}, ${DATE.format(date)}"
        }
    }

    /**
     * How long the shift lasts, as "12h" or "11h30".
     *
     * Derived here rather than in the browser because the rule that decides it is a server rule:
     * an end time that is not after the start means the shift crosses midnight. That derivation
     * already exists in the review path, and two implementations of it would disagree the first
     * time one of them changed.
     */
    fun duration(
        start: LocalTime?,
        end: LocalTime?,
        endsNextDay: Boolean,
    ): String {
        if (start == null || end == null) return EMPTY
        val span = Duration.between(start, end).toMinutes()
        val minutes = if (endsNextDay || span <= 0) span + MINUTES_PER_DAY else span
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        return if (rest == 0L) "${hours}h" else "${hours}h${rest.toString().padStart(2, '0')}"
    }

    /** "25/08 19:00–07:00 (+1)" — the shape she reads on a phone, not an ISO timestamp. */
    fun window(
        date: LocalDate?,
        start: LocalTime?,
        end: LocalTime?,
        endsNextDay: Boolean,
    ): String {
        val datePart = date?.let { DATE.format(it) } ?: "data?"
        val hours =
            when {
                start == null && end == null -> "horário?"
                start != null && end != null -> "${TIME.format(start)}–${TIME.format(end)}"
                start != null -> "a partir de ${TIME.format(start)}"
                else -> "até ${TIME.format(end)}"
            }
        val spillover = if (endsNextDay) " (+1)" else EMPTY
        return "$datePart $hours$spillover"
    }

    fun money(
        amount: java.math.BigDecimal?,
        currency: String?,
    ): String =
        amount?.let {
            val symbol = if (currency == null || currency == "BRL") "R$" else currency
            // DecimalFormat is not thread-safe, and this formatter is shared across requests.
            val decimal = java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(BRAZIL))
            "$symbol ${decimal.format(it)}"
        } ?: EMPTY

    private companion object {
        const val EMPTY = ""
        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_PER_DAY = 1440L
        val BRAZIL: java.util.Locale = java.util.Locale.of("pt", "BR")
        val MOMENT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
        val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", BRAZIL)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

data class OpportunityView(
    val id: String,
    val status: String,
    /** Drives the badge colour, and nothing else — the label is the status itself. */
    val tone: String,
    val window: String,
    val place: String,
    val amount: String,
    val detectedAt: String,
    val readByModel: Boolean,
    val confidence: String,
    val ambiguousFields: String,
    val resolutionReason: String?,
    val reviewNote: String?,
    val sourceText: String,
    val senderName: String,
    val version: Int,
    val claimable: Boolean,
    val openForAnalysis: Boolean,
)

data class ClaimView(
    val id: String,
    val opportunityId: String,
    val status: String,
    val tone: String,
    val mode: String,
    val message: String,
    val decidedAt: String,
    val claimedAt: String,
    val failureCode: String?,
    val attemptCount: Int,
    val retryable: Boolean,
    val retractable: Boolean,
)

data class MessageView(
    val id: String,
    val receivedAt: String,
    val senderName: String,
    val chatName: String,
    val text: String,
    val processingStatus: String,
    val ignoredReason: String?,
)

data class CommitmentView(
    val source: String,
    val reference: String?,
    val label: String,
    val window: String,
    val fromClaim: Boolean,
)

internal fun ShiftOpportunityResponse.toView(
    formatter: ConsoleFormatter,
    sourceText: String,
    senderName: String,
): OpportunityView =
    OpportunityView(
        id = id,
        status = status.name,
        tone = status.tone(),
        window = formatter.window(shiftDate, startTime, endTime, endsNextDay),
        place = listOfNotNull(location, city, specialty).joinToString(" · "),
        amount = formatter.money(amount, currency),
        detectedAt = formatter.moment(detectedAt),
        readByModel = extractionMethod.name == "AI_FALLBACK",
        confidence = confidence?.let { "${it.movePointRight(2).toInt()}%" } ?: "",
        ambiguousFields = ambiguousFields.joinToString(", "),
        resolutionReason = resolutionReason,
        reviewNote = reviewNote,
        sourceText = sourceText,
        senderName = senderName,
        version = version,
        claimable = status.name == "ELIGIBLE",
        openForAnalysis = status.name in OPEN_FOR_ANALYSIS,
    )

internal fun ClaimResponse.toView(formatter: ConsoleFormatter): ClaimView =
    ClaimView(
        id = id,
        opportunityId = opportunityId,
        status = status.name,
        tone =
            when (status.name) {
                "CLAIMED" -> "good"
                "FAILED" -> "bad"
                "RETRACTED" -> "muted"
                else -> "pending"
            },
        mode = mode.name,
        message = message,
        decidedAt = formatter.moment(decidedAt),
        claimedAt = formatter.moment(claimedAt),
        failureCode = failureCode,
        attemptCount = attemptCount,
        retryable = status.name == "FAILED",
        retractable = status.name == "CLAIMED",
    )

internal fun IncomingMessageResponse.toView(formatter: ConsoleFormatter): MessageView =
    MessageView(
        id = id,
        receivedAt = formatter.moment(receivedAt),
        senderName = senderName ?: senderId,
        chatName = chatName ?: chatId,
        text = text,
        processingStatus = processingStatus.name,
        ignoredReason = ignoredReason?.name,
    )

internal fun CommitmentResponse.toView(formatter: ConsoleFormatter): CommitmentView =
    CommitmentView(
        source = source.name,
        reference = reference,
        label = label ?: (if (source.name == "CLAIM") "Plantão pego aqui" else "Compromisso"),
        window = formatter.window(shiftDate, startTime, endTime, endsNextDay),
        fromClaim = source.name == "CLAIM",
    )

private val OPEN_FOR_ANALYSIS = setOf("DETECTED", "PARSING", "REVIEW_REQUIRED", "EVALUATING", "ELIGIBLE")

private fun Enum<*>.tone(): String =
    when (name) {
        "ELIGIBLE" -> "good"
        "CLAIMED" -> "good"
        "REVIEW_REQUIRED" -> "warn"
        "REJECTED", "EXPIRED", "CLAIM_FAILED" -> "bad"
        else -> "pending"
    }
