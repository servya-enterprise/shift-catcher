package br.com.shiftcatcher.availability

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.foundation.http.ApiProblemException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Merges every [CommitmentSourcePort] into the one view the rules read and the operator sees.
 *
 * The service knows how many sources there are only because Spring hands it the list; it never
 * names one. That is what lets a calendar adapter (`12-MVP/Calendar-Integration.md`) become a third
 * source without this file changing.
 */
@Service
class AvailabilityService(
    private val repository: AvailabilityEntryRepository,
    private val sources: List<CommitmentSourcePort>,
    private val properties: ShiftCatcherProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Everything that could possibly touch [date].
     *
     * The neighbouring days are not padding: a shift that starts on the 3rd and ends on the 4th
     * occupies a morning it does not share a date with, and so does an offer that runs past
     * midnight. Reading three indexed days is cheaper than being wrong about either.
     *
     * [excludingOpportunityId] keeps an opportunity from colliding with its own claim, which is
     * what a simulation over already-claimed opportunities would otherwise report.
     */
    fun commitmentsAround(
        date: LocalDate,
        excludingOpportunityId: UUID? = null,
    ): List<Commitment> {
        val excluded = excludingOpportunityId?.toString()
        return sources
            .flatMap { it.commitmentsBetween(date.minusDays(1), date.plusDays(1)) }
            .filterNot { it.source == CommitmentSource.CLAIM && it.reference == excluded }
    }

    /** `EP-040`. Defaults to a window around today so the common call needs no parameters. */
    fun list(
        from: LocalDate?,
        toInclusive: LocalDate?,
    ): CommitmentListResponse {
        // Her local date, not the server's: near midnight in UTC the two disagree, and the window
        // she means is the one on her own calendar.
        val today = LocalDate.ofInstant(clock.instant(), properties.detection.timezone)
        val start = from ?: today.minusDays(DEFAULT_PAST_DAYS)
        val end = toInclusive ?: start.plusDays(DEFAULT_WINDOW_DAYS)
        require(!start.isAfter(end)) { "from must not be after to" }
        require(start.plusDays(MAX_WINDOW_DAYS) >= end) { "the window must not exceed $MAX_WINDOW_DAYS days" }

        val commitments =
            sources
                .flatMap { it.commitmentsBetween(start, end) }
                .sortedWith(compareBy({ it.shiftDate }, { it.startTime ?: LocalTime.MIN }, { it.reference }))
        return CommitmentListResponse(
            from = start,
            to = end,
            commitments = commitments.map { it.toResponse() },
            count = commitments.size,
        )
    }

    /** `EP-041`: records a shift she got somewhere other than these groups. */
    fun create(request: CreateAvailabilityRequest): AvailabilityEntryResponse {
        val shiftDate = request.shiftDate ?: throw IllegalArgumentException("shiftDate is required")
        val start = request.startTime
        val end = request.endTime
        // A half-specified window silently degrades every overlap check that reads it, so it is
        // refused rather than stored.
        require((start == null) == (end == null)) {
            "startTime and endTime must be supplied together or omitted together"
        }
        val endsNextDay = request.endsNextDay ?: false
        require(!endsNextDay || start != null) { "endsNextDay requires startTime and endTime" }
        val label = request.label?.takeIf { it.isNotBlank() }
        require((label?.length ?: 0) <= MAX_LABEL) { "label exceeds $MAX_LABEL characters" }

        return repository
            .insert(
                AvailabilityEntryWrite(
                    shiftDate = shiftDate,
                    startTime = start,
                    endTime = end,
                    endsNextDay = endsNextDay,
                    label = label,
                    note = request.note?.takeIf { it.isNotBlank() },
                ),
            ).toResponse()
    }

    /** `EP-042`. Only hand-kept entries can be deleted; a claimed shift is retracted, not erased. */
    fun delete(entryId: String): AvailabilityEntryResponse = repository.delete(parseId(entryId))?.toResponse() ?: throw notFound()

    private fun notFound(): ApiProblemException =
        ApiProblemException(
            status = HttpStatus.NOT_FOUND,
            code = "RESOURCE_NOT_FOUND",
            title = "Availability entry not found",
            message = "No availability entry matches the supplied identifier",
        )

    private fun parseId(entryId: String): UUID =
        runCatching { UUID.fromString(entryId) }
            .getOrElse { throw IllegalArgumentException("entryId must be a UUID") }

    private fun Commitment.toResponse(): CommitmentResponse =
        CommitmentResponse(
            source = source,
            reference = reference,
            label = label,
            shiftDate = shiftDate,
            startTime = startTime,
            endTime = endTime,
            endsNextDay = endsNextDay,
        )

    private fun AvailabilityEntry.toResponse(): AvailabilityEntryResponse =
        AvailabilityEntryResponse(
            id = id.toString(),
            shiftDate = shiftDate,
            startTime = startTime,
            endTime = endTime,
            endsNextDay = endsNextDay,
            label = label,
            note = note,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private companion object {
        const val DEFAULT_PAST_DAYS = 1L
        const val DEFAULT_WINDOW_DAYS = 60L
        const val MAX_WINDOW_DAYS = 366L
        const val MAX_LABEL = 128
    }
}

data class CreateAvailabilityRequest(
    val shiftDate: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val endsNextDay: Boolean? = null,
    val label: String? = null,
    val note: String? = null,
)

data class AvailabilityEntryResponse(
    val id: String,
    val shiftDate: LocalDate,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val endsNextDay: Boolean,
    val label: String?,
    val note: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CommitmentResponse(
    val source: CommitmentSource,
    val reference: String?,
    val label: String?,
    val shiftDate: LocalDate,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val endsNextDay: Boolean,
)

data class CommitmentListResponse(
    val from: LocalDate,
    val to: LocalDate,
    val commitments: List<CommitmentResponse>,
    val count: Int,
)
