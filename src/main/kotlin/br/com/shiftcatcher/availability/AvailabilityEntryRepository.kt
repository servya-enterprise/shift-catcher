package br.com.shiftcatcher.availability

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Time
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The commitments the operator keeps by hand. Shifts claimed through this system are not stored
 * here; they are read live by [ClaimedShiftCommitmentSource].
 */
@Repository
class AvailabilityEntryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findBetween(
        from: LocalDate,
        toInclusive: LocalDate,
    ): List<AvailabilityEntry> =
        jdbcTemplate.query(
            "$SELECT_SQL where shift_date between ? and ? order by shift_date, start_time nulls first, id",
            ROW_MAPPER,
            from,
            toInclusive,
        )

    @Transactional
    fun insert(entry: AvailabilityEntryWrite): AvailabilityEntry =
        jdbcTemplate.queryForObject(
            INSERT_SQL,
            ROW_MAPPER,
            entry.shiftDate,
            entry.startTime?.let(Time::valueOf),
            entry.endTime?.let(Time::valueOf),
            entry.endsNextDay,
            entry.label,
            entry.note,
        )!!

    /** Returns null when the row was already gone, so a repeated delete answers 404 rather than 500. */
    @Transactional
    fun delete(id: UUID): AvailabilityEntry? =
        jdbcTemplate
            .query(
                "delete from availability_entry where id = ? returning $COLUMNS",
                ROW_MAPPER,
                id,
            ).firstOrNull()

    private companion object {
        const val COLUMNS =
            "id, shift_date, start_time, end_time, ends_next_day, label, note, created_at, updated_at"

        val SELECT_SQL = "select $COLUMNS from availability_entry"

        val INSERT_SQL =
            """
            insert into availability_entry (shift_date, start_time, end_time, ends_next_day, label, note)
            values (?, ?, ?, ?, ?, ?)
            returning $COLUMNS
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                AvailabilityEntry(
                    id = resultSet.getObject("id", UUID::class.java),
                    shiftDate = resultSet.getObject("shift_date", LocalDate::class.java),
                    startTime = resultSet.getTime("start_time")?.toLocalTime(),
                    endTime = resultSet.getTime("end_time")?.toLocalTime(),
                    endsNextDay = resultSet.getBoolean("ends_next_day"),
                    label = resultSet.getString("label"),
                    note = resultSet.getString("note"),
                    createdAt = resultSet.getTimestamp("created_at").toInstant(),
                    updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
                )
            }
    }
}

/** Adapts the hand-kept entries to the port the rule engine reads through. */
@org.springframework.stereotype.Component
class ManualCommitmentSource(
    private val repository: AvailabilityEntryRepository,
) : CommitmentSourcePort {
    override fun commitmentsBetween(
        from: LocalDate,
        toInclusive: LocalDate,
    ): List<Commitment> =
        repository.findBetween(from, toInclusive).map {
            Commitment(
                source = CommitmentSource.MANUAL,
                reference = it.id.toString(),
                label = it.label,
                shiftDate = it.shiftDate,
                startTime = it.startTime,
                endTime = it.endTime,
                endsNextDay = it.endsNextDay,
            )
        }
}

data class AvailabilityEntryWrite(
    val shiftDate: LocalDate,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val endsNextDay: Boolean,
    val label: String?,
    val note: String?,
)

data class AvailabilityEntry(
    val id: UUID,
    val shiftDate: LocalDate,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val endsNextDay: Boolean,
    val label: String?,
    val note: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
