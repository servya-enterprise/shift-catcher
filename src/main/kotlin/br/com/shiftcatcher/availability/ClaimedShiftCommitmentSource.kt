package br.com.shiftcatcher.availability

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * The shifts this system already took for her.
 *
 * Read live rather than copied into `availability_entry`. A mirrored row would have to be kept in
 * step with every claim transition, and the first thing that would drift is retraction (`EP-037`):
 * the operator would take a claim back and still be blocked by the ghost of it. Querying the claim
 * is one indexed join and cannot go stale.
 *
 * Only claims that are live or delivered count. `FAILED` never reached the group and `RETRACTED`
 * was taken back, so neither commits her to anything.
 */
@Component
class ClaimedShiftCommitmentSource(
    private val jdbcTemplate: JdbcTemplate,
) : CommitmentSourcePort {
    override fun commitmentsBetween(
        from: LocalDate,
        toInclusive: LocalDate,
    ): List<Commitment> = jdbcTemplate.query(SELECT_SQL, ROW_MAPPER, from, toInclusive)

    private companion object {
        val SELECT_SQL =
            """
            select opportunity.id as opportunity_id,
                   opportunity.shift_date,
                   opportunity.start_time,
                   opportunity.end_time,
                   opportunity.ends_next_day,
                   coalesce(opportunity.location, opportunity.city, opportunity.specialty) as label
              from shift_claim claim
              join shift_opportunity opportunity on opportunity.id = claim.opportunity_id
             where claim.status in ('CREATED', 'SENDING', 'RETRY_PENDING', 'PROVIDER_ACCEPTED', 'CLAIMED')
               and opportunity.shift_date between ? and ?
             order by opportunity.shift_date, opportunity.start_time nulls first, opportunity.id
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                Commitment(
                    source = CommitmentSource.CLAIM,
                    reference = resultSet.getString("opportunity_id"),
                    label = resultSet.getString("label"),
                    shiftDate = resultSet.getObject("shift_date", LocalDate::class.java),
                    startTime = resultSet.getTime("start_time")?.toLocalTime(),
                    endTime = resultSet.getTime("end_time")?.toLocalTime(),
                    endsNextDay = resultSet.getBoolean("ends_next_day"),
                )
            }
    }
}
