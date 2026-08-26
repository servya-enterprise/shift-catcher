package br.com.shiftcatcher.console

import br.com.shiftcatcher.availability.CommitmentResponse
import br.com.shiftcatcher.claim.ClaimResponse
import br.com.shiftcatcher.messaging.IncomingMessageResponse
import br.com.shiftcatcher.shift.ShiftOpportunityResponse
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/*
 * The JSON the operator app reads.
 *
 * Three rules shape every type in this file, and each of them exists because breaking it puts work
 * in the browser that the browser cannot do correctly.
 *
 * Formatting happens here. The window string, the money string and the date eyebrow are built once
 * in Kotlin, in her timezone, from the same [ConsoleFormatter] the server-rendered console already
 * uses. A client that reformats an Instant with the browser's locale shows a different hour on a
 * laptop that has travelled, and there is no second implementation to keep in step.
 *
 * The raw values travel alongside the formatted ones, but only where a form has to be filled. The
 * detail screen needs the ISO date to seed a date input; the list does not, and does not get it.
 *
 * Grouping happens here too. The three buckets on the board are derived from [OpportunityStatus]
 * by the server; the browser never receives a flat list and sorts it, because that would put the
 * meaning of every status into a second place that can drift.
 */

// --- session -------------------------------------------------------------------------------------

data class ConsoleSignInRequest(
    val token: String? = null,
)

/**
 * What a session is worth knowing about.
 *
 * [csrfToken] is here because the session cookie is HttpOnly and the app cannot read it. Without a
 * way to fetch the token again, a page reload would leave the operator authenticated but unable to
 * perform a single action — every unsafe request would take a 403 — and her only remedy would be to
 * sign out and back in after every refresh.
 */
data class ConsoleSessionResponse(
    val authenticated: Boolean,
    val csrfToken: String,
    val expiresInSeconds: Long,
)

// --- board ---------------------------------------------------------------------------------------

/**
 * How the connection to WhatsApp is doing, read from the stored observation.
 *
 * Deliberately [ProviderHealthGate.current], never a live probe: this is polled from an open page
 * every fifteen seconds, and a live probe would spend a rate-limited quota on a screen nobody is
 * looking at.
 */
data class ConsolePulse(
    /** live, degraded or down. */
    val tone: String,
    val label: String,
    val groupCount: Int,
    val lastMessageAt: String,
    val reason: String?,
    /** True when the observation is older than the freshness window, so the tone is a guess. */
    val stale: Boolean,
)

data class ConsoleCounts(
    val go: Int,
    val wait: Int,
    val closed: Int,
)

/** The ISO values behind the window string, sent only where a form has to be seeded. */
data class ConsoleWindowRaw(
    val shiftDate: LocalDate?,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val endsNextDay: Boolean,
)

/** Who said it and where. She recognises the sender before she recognises the shift. */
data class ConsoleProvenance(
    val senderName: String,
    val chatName: String,
    val receivedAt: String,
)

data class ConsoleOfferCard(
    val id: String,
    val version: Int,
    val status: String,
    /** ready, attention, working, sent, closed or failed. The app maps this to a badge family. */
    val tone: String,
    /** "Hoje", "Amanhã" or "qua, 27/08" — the line above the window. */
    val dateEyebrow: String,
    /** "25/08 19:00–07:00 (+1)", or the literals "data?" and "horário?" when unknown. */
    val window: String,
    val durationLabel: String,
    /** Empty string when there is no amount. The app must test for empty, not for null. */
    val money: String,
    val place: String,
    val specialty: String?,
    val readByModel: Boolean,
    val confidencePct: Int?,
    val ambiguousFields: List<String>,
    val provenance: ConsoleProvenance,
    val claimable: Boolean,
    val openForAnalysis: Boolean,
)

data class ConsoleClosedRow(
    val id: String,
    val status: String,
    val tone: String,
    val window: String,
    val money: String,
    val reasonCode: String?,
)

/**
 * The whole board in one call.
 *
 * [atCeiling] is not decoration. The three services behind this response each cap a list at a
 * hundred rows with no cursor and no filter, so a busy day silently truncates. A screen that hides
 * that is claiming completeness it does not have.
 */
data class ConsoleBoardResponse(
    val serverTime: String,
    val pulse: ConsolePulse,
    val counts: ConsoleCounts,
    val go: List<ConsoleOfferCard>,
    val wait: List<ConsoleOfferCard>,
    val closed: List<ConsoleClosedRow>,
    val limit: Int,
    val atCeiling: Boolean,
)

// --- detail --------------------------------------------------------------------------------------

data class ConsoleSource(
    val text: String,
    val senderName: String,
    val chatName: String,
    val receivedAt: String,
    val providerTimestamp: String,
)

/** The values a correction form starts from, in the shapes the inputs expect. */
data class ConsoleEditable(
    val shiftDate: LocalDate?,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val location: String?,
    val city: String?,
    val amount: BigDecimal?,
    val reviewNote: String?,
)

data class ConsoleOpportunityDetail(
    val card: ConsoleOfferCard,
    val windowRaw: ConsoleWindowRaw,
    val editable: ConsoleEditable,
    val source: ConsoleSource?,
    val resolutionReason: String?,
    val reviewNote: String?,
    val currency: String?,
)

data class ConsoleReviewRequest(
    val shiftDate: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val location: String? = null,
    val city: String? = null,
    /** Sent as she typed it, comma or dot. The port normalises; the browser does not do maths. */
    val amount: String? = null,
    val reviewNote: String? = null,
    val version: Int? = null,
)

data class ConsoleIgnoreRequest(
    val reviewNote: String? = null,
    val version: Int? = null,
)

data class ConsoleEvaluationResponse(
    val opportunityId: String,
    val status: String,
    val result: String,
    val reasons: List<String>,
    val evaluatedAt: String,
    val detail: ConsoleOpportunityDetail,
)

// --- claims --------------------------------------------------------------------------------------

data class ConsoleClaimRow(
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

data class ConsoleClaimListResponse(
    val claims: List<ConsoleClaimRow>,
    val count: Int,
    val limit: Int,
    val atCeiling: Boolean,
)

data class ConsoleClaimAck(
    val claim: ConsoleClaimRow,
    /**
     * True when the claim already existed and this call did not create it.
     *
     * The service answers a second claim with 409. Treating that as a failure paints the action
     * that actually worked in red, and this action is the entire product.
     */
    val alreadyClaimed: Boolean,
)

data class ConsoleRetractRequest(
    val reason: String? = null,
)

// --- messages ------------------------------------------------------------------------------------

data class ConsoleMessageRow(
    val id: String,
    val receivedAt: String,
    val senderName: String,
    val chatName: String,
    val text: String,
    val processingStatus: String,
    val ignoredReason: String?,
)

data class ConsoleMessageListResponse(
    val messages: List<ConsoleMessageRow>,
    val count: Int,
    val limit: Int,
    val atCeiling: Boolean,
)

// --- agenda --------------------------------------------------------------------------------------

data class ConsoleCommitmentRow(
    val source: String,
    /**
     * The id to send to DELETE — and only when [removable] is true.
     *
     * For a MANUAL entry this is the availability entry's id. For a CLAIM it is the OPPORTUNITY's
     * id, which the delete endpoint knows nothing about, so sending it would produce a puzzling
     * 404 for a row the operator can see.
     */
    val reference: String?,
    val label: String,
    val dateEyebrow: String,
    val window: String,
    val fromClaim: Boolean,
    val removable: Boolean,
)

data class ConsoleAgendaResponse(
    val from: LocalDate,
    val to: LocalDate,
    val commitments: List<ConsoleCommitmentRow>,
    val count: Int,
)

data class ConsoleCommitmentRequest(
    val shiftDate: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val label: String? = null,
    val note: String? = null,
)

// --- settings ------------------------------------------------------------------------------------

data class ConsoleClaimMessageResponse(
    val message: String,
    val version: Int,
    val updatedAt: String,
)

data class ConsoleClaimMessageRequest(
    val message: String? = null,
    val version: Int? = null,
)

// --- mapping -------------------------------------------------------------------------------------

/**
 * Maps a status onto the six tones the app knows how to paint.
 *
 * Six, not ten, because the operator is answering one question per card: is there something for me
 * to do. The distinction between DETECTED and PARSING matters to the pipeline and to nobody
 * holding a phone.
 */
internal fun consoleTone(status: String): String =
    when (status) {
        "ELIGIBLE" -> "ready"
        "REVIEW_REQUIRED" -> "attention"
        "DETECTED", "PARSING", "EVALUATING", "CLAIM_PENDING" -> "working"
        "CLAIMED" -> "sent"
        "CLAIM_FAILED" -> "failed"
        else -> "closed"
    }

internal fun consoleClaimTone(status: String): String =
    when (status) {
        "CLAIMED" -> "sent"
        "FAILED" -> "failed"
        "RETRACTED" -> "closed"
        else -> "working"
    }

internal fun ShiftOpportunityResponse.toCard(
    formatter: ConsoleFormatter,
    source: IncomingMessageResponse?,
): ConsoleOfferCard =
    ConsoleOfferCard(
        id = id,
        version = version,
        status = status.name,
        tone = consoleTone(status.name),
        dateEyebrow = formatter.dateEyebrow(shiftDate),
        window = formatter.window(shiftDate, startTime, endTime, endsNextDay),
        durationLabel = formatter.duration(startTime, endTime, endsNextDay),
        money = formatter.money(amount, currency),
        place = listOfNotNull(location, city).joinToString(" · "),
        specialty = specialty,
        readByModel = extractionMethod.name == "AI_FALLBACK",
        confidencePct = confidence?.movePointRight(2)?.toInt(),
        ambiguousFields = ambiguousFields,
        provenance =
            ConsoleProvenance(
                senderName = source?.let { it.senderName ?: it.senderId } ?: "",
                chatName = source?.let { it.chatName ?: it.chatId } ?: "",
                receivedAt = formatter.moment(source?.receivedAt),
            ),
        claimable = status.name == "ELIGIBLE",
        openForAnalysis = status.isOpenForAnalysis(),
    )

internal fun ShiftOpportunityResponse.toClosedRow(formatter: ConsoleFormatter): ConsoleClosedRow =
    ConsoleClosedRow(
        id = id,
        status = status.name,
        tone = consoleTone(status.name),
        window = formatter.window(shiftDate, startTime, endTime, endsNextDay),
        money = formatter.money(amount, currency),
        reasonCode = resolutionReason,
    )

internal fun ShiftOpportunityResponse.toDetail(
    formatter: ConsoleFormatter,
    source: IncomingMessageResponse?,
): ConsoleOpportunityDetail =
    ConsoleOpportunityDetail(
        card = toCard(formatter, source),
        windowRaw =
            ConsoleWindowRaw(
                shiftDate = shiftDate,
                startTime = startTime,
                endTime = endTime,
                endsNextDay = endsNextDay,
            ),
        editable =
            ConsoleEditable(
                shiftDate = shiftDate,
                startTime = startTime,
                endTime = endTime,
                location = location,
                city = city,
                amount = amount,
                reviewNote = reviewNote,
            ),
        source =
            source?.let {
                ConsoleSource(
                    text = it.text,
                    senderName = it.senderName ?: it.senderId,
                    chatName = it.chatName ?: it.chatId,
                    receivedAt = formatter.moment(it.receivedAt),
                    providerTimestamp = formatter.moment(it.providerTimestamp),
                )
            },
        resolutionReason = resolutionReason,
        reviewNote = reviewNote,
        currency = currency,
    )

internal fun ClaimResponse.toRow(formatter: ConsoleFormatter): ConsoleClaimRow =
    ConsoleClaimRow(
        id = id,
        opportunityId = opportunityId,
        status = status.name,
        tone = consoleClaimTone(status.name),
        mode = mode.name,
        message = message,
        decidedAt = formatter.moment(decidedAt),
        claimedAt = formatter.moment(claimedAt),
        failureCode = failureCode,
        attemptCount = attemptCount,
        retryable = status.name == "FAILED",
        retractable = status.isRetractable(),
    )

internal fun IncomingMessageResponse.toRow(formatter: ConsoleFormatter): ConsoleMessageRow =
    ConsoleMessageRow(
        id = id,
        receivedAt = formatter.moment(receivedAt),
        senderName = senderName ?: senderId,
        chatName = chatName ?: chatId,
        text = text,
        processingStatus = processingStatus.name,
        ignoredReason = ignoredReason?.name,
    )

internal fun CommitmentResponse.toRow(formatter: ConsoleFormatter): ConsoleCommitmentRow =
    ConsoleCommitmentRow(
        source = source.name,
        reference = reference,
        label = label ?: (if (source.name == "CLAIM") "Plantão pego aqui" else "Compromisso"),
        dateEyebrow = formatter.dateEyebrow(shiftDate),
        window = formatter.window(shiftDate, startTime, endTime, endsNextDay),
        fromClaim = source.name == "CLAIM",
        // A claimed shift is removed by retracting the claim, not by deleting a calendar row that
        // does not exist: the CLAIM source is read live from the claim table, never copied.
        removable = source.name == "MANUAL" && reference != null,
    )
