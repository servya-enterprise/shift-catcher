package br.com.shiftcatcher.console

import br.com.shiftcatcher.availability.CommitmentResponse
import br.com.shiftcatcher.claim.ClaimResponse
import br.com.shiftcatcher.messaging.IncomingMessageResponse
import br.com.shiftcatcher.shift.ShiftOpportunityResponse
import java.time.Instant
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
) {
    fun moment(instant: Instant?): String = instant?.let { MOMENT.format(it.atZone(zone)) } ?: EMPTY

    /** "25/08 19:00–07:00 (+1)" — the shape she reads on a phone, not an ISO timestamp. */
    fun window(
        date: java.time.LocalDate?,
        start: java.time.LocalTime?,
        end: java.time.LocalTime?,
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
        val BRAZIL: java.util.Locale = java.util.Locale.of("pt", "BR")
        val MOMENT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
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
