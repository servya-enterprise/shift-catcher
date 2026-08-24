package br.com.shiftcatcher.shift

import br.com.shiftcatcher.foundation.http.ApiProblemException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Service
class ShiftOpportunityService(
    private val repository: ShiftOpportunityRepository,
) {
    fun list(): ShiftOpportunityListResponse {
        val opportunities = repository.findRecent(MAX_LIST_SIZE).map { it.toResponse() }
        return ShiftOpportunityListResponse(
            opportunities = opportunities,
            count = opportunities.size,
            limit = MAX_LIST_SIZE,
        )
    }

    fun detail(opportunityId: String): ShiftOpportunityResponse = load(opportunityId).toResponse()

    /**
     * Manual review (`UC-05`): the operator supplies what the parser could not resolve. Whatever is
     * left unanswered stays ambiguous, so a half-filled review cannot promote an opportunity.
     */
    fun review(
        opportunityId: String,
        request: ReviewOpportunityRequest,
    ): ShiftOpportunityResponse {
        val current = load(opportunityId)
        require(current.status.isOpenForAnalysis()) {
            "Only an opportunity that is still open for analysis can be reviewed"
        }
        val version = requiredVersion(request.version)

        val shiftDate = request.shiftDate?.let(::parseDate) ?: current.shiftDate
        val startTime = request.startTime?.let(::parseTime) ?: current.startTime
        val endTime = request.endTime?.let(::parseTime) ?: current.endTime
        val ambiguous = mutableListOf<String>()
        if (shiftDate == null) ambiguous += "shiftDate"
        if (startTime == null) ambiguous += "startTime"
        if (endTime == null) ambiguous += "endTime"

        return applyDecision(
            current = current,
            expectedVersion = version,
            decision =
                ShiftOpportunityDecision(
                    status =
                        if (ambiguous.isEmpty()) OpportunityStatus.EVALUATING else OpportunityStatus.REVIEW_REQUIRED,
                    shiftDate = shiftDate,
                    startTime = startTime,
                    endTime = endTime,
                    endsNextDay =
                        if (startTime != null && endTime != null) {
                            !endTime.isAfter(startTime)
                        } else {
                            current.endsNextDay
                        },
                    location = request.location ?: current.location,
                    city = request.city ?: current.city,
                    amount = request.amount ?: current.amount,
                    currency = request.currency ?: current.currency ?: request.amount?.let { "BRL" },
                    specialty = request.specialty ?: current.specialty,
                    notes = request.notes ?: current.notes,
                    extractionMethod = ExtractionMethod.MANUAL_REVIEW,
                    // A human confirmation is the strongest signal available at this stage.
                    confidence = if (ambiguous.isEmpty()) BigDecimal.ONE else current.confidence,
                    ambiguousFields = ambiguous.toList(),
                    resolutionReason = if (ambiguous.isEmpty()) null else "ESSENTIAL_FIELD_AMBIGUOUS",
                    reviewNote = request.reviewNote ?: current.reviewNote,
                ),
        )
    }

    /** `EP-022`: the operator discards the opportunity. `REJECTED` is the state machine's terminal no. */
    fun ignore(
        opportunityId: String,
        request: IgnoreOpportunityRequest?,
    ): ShiftOpportunityResponse {
        val current = load(opportunityId)
        require(current.status != OpportunityStatus.CLAIMED) {
            "A claimed opportunity cannot be ignored"
        }
        if (current.status == OpportunityStatus.REJECTED) {
            return current.toResponse()
        }
        return applyDecision(
            current = current,
            expectedVersion = request?.version ?: current.version,
            decision =
                ShiftOpportunityDecision(
                    status = OpportunityStatus.REJECTED,
                    shiftDate = current.shiftDate,
                    startTime = current.startTime,
                    endTime = current.endTime,
                    endsNextDay = current.endsNextDay,
                    location = current.location,
                    city = current.city,
                    amount = current.amount,
                    currency = current.currency,
                    specialty = current.specialty,
                    notes = current.notes,
                    extractionMethod = current.extractionMethod,
                    confidence = current.confidence,
                    ambiguousFields = current.ambiguousFields,
                    resolutionReason = "MANUALLY_IGNORED",
                    reviewNote = request?.reviewNote ?: current.reviewNote,
                ),
        )
    }

    private fun applyDecision(
        current: ShiftOpportunity,
        expectedVersion: Int,
        decision: ShiftOpportunityDecision,
    ): ShiftOpportunityResponse =
        repository.updateDecision(current.id, expectedVersion, decision)?.toResponse()
            ?: throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "STALE_VERSION",
                title = "Stale version",
                message = "The opportunity was modified by another request; reload it and retry",
            )

    private fun requiredVersion(version: Int?): Int =
        version ?: throw ApiProblemException(
            status = HttpStatus.BAD_REQUEST,
            code = "INVALID_REQUEST",
            title = "Invalid request",
            message = "version is required so a concurrent change is never overwritten silently",
        )

    private fun load(opportunityId: String): ShiftOpportunity =
        repository.findById(parseId(opportunityId))
            ?: throw ApiProblemException(
                status = HttpStatus.NOT_FOUND,
                code = "RESOURCE_NOT_FOUND",
                title = "Opportunity not found",
                message = "No shift opportunity matches the supplied identifier",
            )

    private fun parseId(opportunityId: String): UUID =
        runCatching { UUID.fromString(opportunityId) }
            .getOrElse { throw IllegalArgumentException("opportunityId must be a UUID") }

    private fun parseDate(raw: String): LocalDate =
        runCatching { LocalDate.parse(raw) }
            .getOrElse { throw IllegalArgumentException("shiftDate must be an ISO date") }

    private fun parseTime(raw: String): LocalTime =
        runCatching { LocalTime.parse(raw) }
            .getOrElse { throw IllegalArgumentException("time fields must be ISO local times") }

    private companion object {
        const val MAX_LIST_SIZE = 100
    }
}

private fun ShiftOpportunity.toResponse(): ShiftOpportunityResponse =
    ShiftOpportunityResponse(
        id = id.toString(),
        sourceMessageId = sourceMessageId.toString(),
        groupId = groupId?.toString(),
        status = status,
        shiftDate = shiftDate,
        startTime = startTime,
        endTime = endTime,
        endsNextDay = endsNextDay,
        location = location,
        city = city,
        amount = amount,
        currency = currency,
        specialty = specialty,
        notes = notes,
        extractionMethod = extractionMethod,
        confidence = confidence,
        ambiguousFields = ambiguousFields,
        resolutionReason = resolutionReason,
        reviewNote = reviewNote,
        version = version,
        detectedAt = detectedAt,
        extractionCompletedAt = extractionCompletedAt,
    )

data class ReviewOpportunityRequest(
    val shiftDate: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val location: String? = null,
    val city: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val specialty: String? = null,
    val notes: String? = null,
    val reviewNote: String? = null,
    val version: Int? = null,
)

data class IgnoreOpportunityRequest(
    val reviewNote: String? = null,
    val version: Int? = null,
)

data class ShiftOpportunityResponse(
    val id: String,
    val sourceMessageId: String,
    val groupId: String?,
    val status: OpportunityStatus,
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
    val extractionMethod: ExtractionMethod,
    val confidence: BigDecimal?,
    val ambiguousFields: List<String>,
    val resolutionReason: String?,
    val reviewNote: String?,
    val version: Int,
    val detectedAt: Instant,
    val extractionCompletedAt: Instant?,
)

data class ShiftOpportunityListResponse(
    val opportunities: List<ShiftOpportunityResponse>,
    val count: Int,
    val limit: Int,
)
