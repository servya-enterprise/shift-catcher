package br.com.shiftcatcher.shift

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Repository
class ShiftOpportunityRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * `05-Data/Data-Model.md` allows exactly one opportunity per source message in the POC, so a
     * re-analysis of the same message updates the existing row instead of creating a rival one.
     */
    @Transactional
    fun upsert(opportunity: ShiftOpportunityWrite): ShiftOpportunity =
        jdbcTemplate.queryForObject(
            UPSERT_SQL,
            ROW_MAPPER,
            opportunity.sourceMessageId,
            opportunity.groupId,
            opportunity.status.name,
            opportunity.shiftDate,
            opportunity.startTime,
            opportunity.endTime,
            opportunity.endsNextDay,
            opportunity.location,
            opportunity.city,
            opportunity.amount,
            opportunity.currency,
            opportunity.specialty,
            opportunity.notes,
            opportunity.extractionMethod.name,
            opportunity.confidence,
            opportunity.ambiguousFields.joinToString(","),
            opportunity.resolutionReason,
            Timestamp.from(opportunity.detectedAt),
            opportunity.extractionCompletedAt?.let(Timestamp::from),
        )!!

    /**
     * Optimistic decision update used by manual review and ignore. Returns null when the supplied
     * version no longer matches, so a concurrent change is reported instead of overwritten.
     */
    @Transactional
    fun updateDecision(
        id: UUID,
        expectedVersion: Int,
        decision: ShiftOpportunityDecision,
    ): ShiftOpportunity? =
        jdbcTemplate
            .query(
                UPDATE_DECISION_SQL,
                ROW_MAPPER,
                decision.status.name,
                decision.shiftDate,
                decision.startTime,
                decision.endTime,
                decision.endsNextDay,
                decision.location,
                decision.city,
                decision.amount,
                decision.currency,
                decision.specialty,
                decision.notes,
                decision.extractionMethod.name,
                decision.confidence,
                decision.ambiguousFields.joinToString(","),
                decision.resolutionReason,
                decision.reviewNote,
                id,
                expectedVersion,
            ).firstOrNull()

    /**
     * Status-only transition used by the rule engine, which decides eligibility without touching the
     * extracted fields it judged.
     */
    @Transactional
    fun updateStatus(
        id: UUID,
        expectedVersion: Int,
        status: OpportunityStatus,
        resolutionReason: String?,
    ): ShiftOpportunity? =
        jdbcTemplate
            .query(UPDATE_STATUS_SQL, ROW_MAPPER, status.name, resolutionReason, id, expectedVersion)
            .firstOrNull()

    fun findById(id: UUID): ShiftOpportunity? = jdbcTemplate.query("$SELECT_SQL where id = ?", ROW_MAPPER, id).firstOrNull()

    fun findBySourceMessageId(sourceMessageId: UUID): ShiftOpportunity? =
        jdbcTemplate.query("$SELECT_SQL where source_message_id = ?", ROW_MAPPER, sourceMessageId).firstOrNull()

    fun findRecent(limit: Int): List<ShiftOpportunity> =
        jdbcTemplate.query("$SELECT_SQL order by detected_at desc, id desc limit ?", ROW_MAPPER, limit)

    private companion object {
        val SELECT_SQL =
            """
            select id, source_message_id, group_id, status, shift_date, start_time, end_time,
                   ends_next_day, location, city, amount, currency, specialty, notes,
                   extraction_method, confidence, ambiguous_fields, resolution_reason, review_note,
                   version, detected_at, extraction_completed_at
              from shift_opportunity
            """.trimIndent()

        val UPSERT_SQL =
            """
            insert into shift_opportunity (
                source_message_id, group_id, status, shift_date, start_time, end_time,
                ends_next_day, location, city, amount, currency, specialty, notes,
                extraction_method, confidence, ambiguous_fields, resolution_reason,
                detected_at, extraction_completed_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (source_message_id) do update
               set group_id = excluded.group_id,
                   status = excluded.status,
                   shift_date = excluded.shift_date,
                   start_time = excluded.start_time,
                   end_time = excluded.end_time,
                   ends_next_day = excluded.ends_next_day,
                   location = excluded.location,
                   city = excluded.city,
                   amount = excluded.amount,
                   currency = excluded.currency,
                   specialty = excluded.specialty,
                   notes = excluded.notes,
                   extraction_method = excluded.extraction_method,
                   confidence = excluded.confidence,
                   ambiguous_fields = excluded.ambiguous_fields,
                   resolution_reason = excluded.resolution_reason,
                   extraction_completed_at = excluded.extraction_completed_at,
                   version = shift_opportunity.version + 1,
                   updated_at = current_timestamp
            returning id, source_message_id, group_id, status, shift_date, start_time, end_time,
                      ends_next_day, location, city, amount, currency, specialty, notes,
                      extraction_method, confidence, ambiguous_fields, resolution_reason,
                      review_note, version, detected_at, extraction_completed_at
            """.trimIndent()

        val UPDATE_DECISION_SQL =
            """
            update shift_opportunity
               set status = ?,
                   shift_date = ?,
                   start_time = ?,
                   end_time = ?,
                   ends_next_day = ?,
                   location = ?,
                   city = ?,
                   amount = ?,
                   currency = ?,
                   specialty = ?,
                   notes = ?,
                   extraction_method = ?,
                   confidence = ?,
                   ambiguous_fields = ?,
                   resolution_reason = ?,
                   review_note = ?,
                   version = version + 1,
                   updated_at = current_timestamp
             where id = ?
               and version = ?
            returning id, source_message_id, group_id, status, shift_date, start_time, end_time,
                      ends_next_day, location, city, amount, currency, specialty, notes,
                      extraction_method, confidence, ambiguous_fields, resolution_reason,
                      review_note, version, detected_at, extraction_completed_at
            """.trimIndent()

        val UPDATE_STATUS_SQL =
            """
            update shift_opportunity
               set status = ?,
                   resolution_reason = ?,
                   version = version + 1,
                   updated_at = current_timestamp
             where id = ?
               and version = ?
            returning id, source_message_id, group_id, status, shift_date, start_time, end_time,
                      ends_next_day, location, city, amount, currency, specialty, notes,
                      extraction_method, confidence, ambiguous_fields, resolution_reason,
                      review_note, version, detected_at, extraction_completed_at
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                ShiftOpportunity(
                    id = resultSet.getObject("id", UUID::class.java),
                    sourceMessageId = resultSet.getObject("source_message_id", UUID::class.java),
                    groupId = resultSet.getObject("group_id", UUID::class.java),
                    status = OpportunityStatus.valueOf(resultSet.getString("status")),
                    shiftDate = resultSet.getObject("shift_date", LocalDate::class.java),
                    startTime = resultSet.getObject("start_time", LocalTime::class.java),
                    endTime = resultSet.getObject("end_time", LocalTime::class.java),
                    endsNextDay = resultSet.getBoolean("ends_next_day"),
                    location = resultSet.getString("location"),
                    city = resultSet.getString("city"),
                    amount = resultSet.getBigDecimal("amount"),
                    currency = resultSet.getString("currency"),
                    specialty = resultSet.getString("specialty"),
                    notes = resultSet.getString("notes"),
                    extractionMethod = ExtractionMethod.valueOf(resultSet.getString("extraction_method")),
                    confidence = resultSet.getBigDecimal("confidence"),
                    ambiguousFields =
                        resultSet.getString("ambiguous_fields").split(",").filter { it.isNotBlank() },
                    resolutionReason = resultSet.getString("resolution_reason"),
                    reviewNote = resultSet.getString("review_note"),
                    version = resultSet.getInt("version"),
                    detectedAt = resultSet.getTimestamp("detected_at").toInstant(),
                    extractionCompletedAt = resultSet.getTimestamp("extraction_completed_at")?.toInstant(),
                )
            }
    }
}

/** `04-Domain/State-Machines.md`. */
enum class OpportunityStatus {
    DETECTED,
    PARSING,
    REVIEW_REQUIRED,
    EVALUATING,
    REJECTED,
    ELIGIBLE,
    CLAIM_PENDING,
    CLAIMED,
    CLAIM_FAILED,
    EXPIRED,
    ;

    /** Once a human or the claim engine has decided, re-analysis must not silently overwrite it. */
    fun isOpenForAnalysis(): Boolean = this in setOf(DETECTED, PARSING, REVIEW_REQUIRED, EVALUATING)
}

enum class ExtractionMethod {
    DETERMINISTIC,
    AI_FALLBACK,
    MANUAL_REVIEW,
}

data class ShiftOpportunityWrite(
    val sourceMessageId: UUID,
    val groupId: UUID?,
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
    val detectedAt: Instant,
    val extractionCompletedAt: Instant?,
)

data class ShiftOpportunityDecision(
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
)

data class ShiftOpportunity(
    val id: UUID,
    val sourceMessageId: UUID,
    val groupId: UUID?,
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
