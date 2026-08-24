package br.com.shiftcatcher.availability

import java.time.LocalDate
import java.time.LocalTime

/**
 * A block of time the operator is already committed to.
 *
 * The `availability` port of `02-Architecture/Module-Map.md`, finally implemented. It answers one
 * question — "what does she already have?" — and deliberately does not answer "should she take
 * this?", which stays in `rules` where every other hard rule lives.
 */
data class Commitment(
    val source: CommitmentSource,
    /** The row this came from, so a conflict can be explained rather than merely asserted. */
    val reference: String?,
    val label: String?,
    val shiftDate: LocalDate,
    /** Null on both ends means an all-day commitment: known to exist, not placed in the day. */
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val endsNextDay: Boolean,
) {
    fun hasWindow(): Boolean = startTime != null && endTime != null
}

enum class CommitmentSource {
    /** Typed in by the operator: the shifts she got somewhere other than these groups. */
    MANUAL,

    /** Claimed through this system. Read live, never copied — see [ClaimedShiftCommitmentSource]. */
    CLAIM,
}

/**
 * One place commitments can come from.
 *
 * Plural on purpose. Today there are two implementations and neither knows about the other; an
 * external calendar is the next one (`12-MVP/Calendar-Integration.md`) and joins by being a bean,
 * without touching the rule engine or the service that merges them.
 */
interface CommitmentSourcePort {
    fun commitmentsBetween(
        from: LocalDate,
        toInclusive: LocalDate,
    ): List<Commitment>
}
