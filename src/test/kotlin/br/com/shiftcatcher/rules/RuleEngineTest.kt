package br.com.shiftcatcher.rules

import br.com.shiftcatcher.availability.Commitment
import br.com.shiftcatcher.availability.CommitmentSource
import br.com.shiftcatcher.shift.ExtractionMethod
import br.com.shiftcatcher.shift.OpportunityStatus
import br.com.shiftcatcher.shift.ShiftOpportunity
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleEngineTest {
    private val engine = RuleEngine()
    private val now = Instant.parse("2026-08-24T22:00:00Z")

    @Test
    fun `a rule set that configures nothing rejects nothing`() {
        val outcome = engine.evaluate(context(RuleDefinition()))

        assertEquals(EvaluationResult.ELIGIBLE, outcome.result)
        assertFalse(outcome.autoClaimAllowed, "auto-claim still needs both switches")
    }

    @Test
    fun `an ambiguous extraction is never eligible`() {
        val outcome = engine.evaluate(context(RuleDefinition(), opportunity(ambiguousFields = listOf("startTime"))))

        assertEquals(EvaluationResult.REVIEW_REQUIRED, outcome.result)
        assertTrue(RuleReason.EXTRACTION_AMBIGUOUS in outcome.reasons)
    }

    @Test
    fun `a missing required field asks for review instead of rejecting`() {
        val outcome =
            engine.evaluate(
                context(RuleDefinition(requiredFields = listOf("city")), opportunity(city = null)),
            )

        assertEquals(EvaluationResult.REVIEW_REQUIRED, outcome.result)
        assertTrue(RuleReason.REQUIRED_FIELD_MISSING in outcome.reasons)
    }

    @Test
    fun `low confidence is uncertainty, not a rejection`() {
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(minConfidence = BigDecimal("0.9")),
                    opportunity(confidence = BigDecimal("0.5")),
                ),
            )

        assertEquals(EvaluationResult.REVIEW_REQUIRED, outcome.result)
        assertTrue(RuleReason.CONFIDENCE_BELOW_MINIMUM in outcome.reasons)
    }

    @Test
    fun `preference mismatches are rejections`() {
        val cases =
            listOf(
                RuleDefinition(allowedWeekdays = listOf(DayOfWeek.SUNDAY)) to RuleReason.WEEKDAY_NOT_ALLOWED,
                RuleDefinition(earliestStartTime = LocalTime.of(20, 0)) to RuleReason.START_TIME_OUTSIDE_WINDOW,
                RuleDefinition(latestStartTime = LocalTime.of(8, 0)) to RuleReason.START_TIME_OUTSIDE_WINDOW,
                RuleDefinition(maxDurationHours = 6) to RuleReason.DURATION_ABOVE_MAXIMUM,
                RuleDefinition(minAmount = BigDecimal("2000")) to RuleReason.AMOUNT_BELOW_MINIMUM,
                RuleDefinition(allowedCities = listOf("Sao Paulo")) to RuleReason.CITY_NOT_ALLOWED,
                RuleDefinition(blockedLocations = listOf("PS Central")) to RuleReason.LOCATION_BLOCKED,
            )
        cases.forEach { (definition, expectedReason) ->
            val outcome = engine.evaluate(context(definition))
            assertEquals(EvaluationResult.REJECTED, outcome.result, "for $expectedReason")
            assertTrue(expectedReason in outcome.reasons, "expected $expectedReason, got ${outcome.reasons}")
        }
    }

    @Test
    fun `an overnight shift is measured across midnight`() {
        // 19:00 to 07:00 is twelve hours, not a negative span.
        val allowed = engine.evaluate(context(RuleDefinition(maxDurationHours = 12)))
        assertEquals(EvaluationResult.ELIGIBLE, allowed.result)

        val tooLong = engine.evaluate(context(RuleDefinition(maxDurationHours = 11)))
        assertEquals(EvaluationResult.REJECTED, tooLong.result)
    }

    @Test
    fun `a stale message is rejected`() {
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(maxMessageAgeMinutes = 30),
                    messageTimestamp = now.minusSeconds(3600),
                ),
            )

        assertEquals(EvaluationResult.REJECTED, outcome.result)
        assertTrue(RuleReason.MESSAGE_TOO_OLD in outcome.reasons)
    }

    @Test
    fun `a disabled group is rejected even when everything else fits`() {
        val outcome = engine.evaluate(context(RuleDefinition(), groupEnabled = false))

        assertEquals(EvaluationResult.REJECTED, outcome.result)
        assertTrue(RuleReason.GROUP_DISABLED in outcome.reasons)
    }

    @Test
    fun `an unreachable provider blocks eligibility without discarding the offer`() {
        val definition = RuleDefinition(requireOperationalInstance = true)

        val down = engine.evaluate(context(definition, instanceOperational = false))
        assertEquals(EvaluationResult.REVIEW_REQUIRED, down.result)
        assertTrue(RuleReason.INSTANCE_NOT_OPERATIONAL in down.reasons)

        // Null means the caller did not observe the provider at this stage. Evaluation judges the
        // offer; blocking here would demand a human for something the claim engine fixes by itself.
        val notObserved = engine.evaluate(context(definition, instanceOperational = null))
        assertEquals(EvaluationResult.ELIGIBLE, notObserved.result)

        val up = engine.evaluate(context(definition, instanceOperational = true))
        assertEquals(EvaluationResult.ELIGIBLE, up.result)
    }

    @Test
    fun `auto-claim needs the global switch and the group switch`() {
        val enabled = RuleDefinition(autoClaimEnabled = true)

        assertFalse(engine.evaluate(context(RuleDefinition(), groupAutoClaimEnabled = true)).autoClaimAllowed)
        assertFalse(engine.evaluate(context(enabled, groupAutoClaimEnabled = false)).autoClaimAllowed)
        assertTrue(engine.evaluate(context(enabled, groupAutoClaimEnabled = true)).autoClaimAllowed)
    }

    @Test
    fun `an ineligible opportunity can never be auto-claimed`() {
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(autoClaimEnabled = true, minAmount = BigDecimal("2000")),
                    groupAutoClaimEnabled = true,
                ),
            )

        assertEquals(EvaluationResult.REJECTED, outcome.result)
        assertFalse(outcome.autoClaimAllowed)
    }

    @Test
    fun `an AI extraction buys no privilege`() {
        // A confident model answer is still judged by the same hard rules.
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(autoClaimEnabled = true, minAmount = BigDecimal("2000")),
                    opportunity(
                        extractionMethod = ExtractionMethod.AI_FALLBACK,
                        confidence = BigDecimal("0.99"),
                    ),
                    groupAutoClaimEnabled = true,
                ),
            )

        assertEquals(EvaluationResult.REJECTED, outcome.result)
        assertFalse(outcome.autoClaimAllowed)
        assertTrue(RuleReason.AMOUNT_BELOW_MINIMUM in outcome.reasons)
    }

    @Test
    fun `an AI reading is eligible but not automatically claimable by default`() {
        val definition = RuleDefinition(autoClaimEnabled = true)
        val aiRead = opportunity(extractionMethod = ExtractionMethod.AI_FALLBACK)

        val guarded = engine.evaluate(context(definition, aiRead, groupAutoClaimEnabled = true))

        assertEquals(EvaluationResult.ELIGIBLE, guarded.result, "a human can still claim it in one call")
        assertFalse(guarded.autoClaimAllowed, "but the model does not get to send on its own")
        assertTrue(RuleReason.AUTO_CLAIM_DISABLED_FOR_AI_EXTRACTION in guarded.reasons)

        val permitted =
            engine.evaluate(
                context(
                    definition.copy(allowAutoClaimFromAi = true),
                    aiRead,
                    groupAutoClaimEnabled = true,
                ),
            )
        assertTrue(permitted.autoClaimAllowed, "the operator can grant it explicitly")
    }

    @Test
    fun `a deterministic reading is unaffected by the AI gate`() {
        val outcome =
            engine.evaluate(
                context(RuleDefinition(autoClaimEnabled = true), groupAutoClaimEnabled = true),
            )

        assertTrue(outcome.autoClaimAllowed)
        assertTrue(RuleReason.AUTO_CLAIM_DISABLED_FOR_AI_EXTRACTION !in outcome.reasons)
    }

    @Test
    fun `a rejection outranks a review when both apply`() {
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(minConfidence = BigDecimal("0.9"), minAmount = BigDecimal("2000")),
                    opportunity(confidence = BigDecimal("0.1")),
                ),
            )

        assertEquals(EvaluationResult.REJECTED, outcome.result)
        assertTrue(RuleReason.AMOUNT_BELOW_MINIMUM in outcome.reasons)
        assertTrue(RuleReason.CONFIDENCE_BELOW_MINIMUM in outcome.reasons, "both reasons stay recorded")
    }

    @Test
    fun `a rule that blows up fails safe to review`() {
        // requiredFields is validated on write; a value that slipped past must not approve anything.
        val outcome = engine.evaluate(context(RuleDefinition(requiredFields = listOf("nonexistentField"))))

        assertEquals(EvaluationResult.REVIEW_REQUIRED, outcome.result)
        assertEquals(listOf(RuleReason.RULE_EVALUATION_FAILED), outcome.reasons)
        assertFalse(outcome.autoClaimAllowed)
    }

    @Test
    fun `an unconfigured agenda rule reads nothing into a full diary`() {
        // The port may well have handed us commitments; without a policy they mean nothing, which
        // is what keeps rule set v1 evaluating exactly as it did before this rule existed.
        val outcome = engine.evaluate(context(RuleDefinition(), commitments = listOf(commitment())))

        assertEquals(EvaluationResult.ELIGIBLE, outcome.result)
        assertTrue(outcome.reasons.none { it.startsWith("AGENDA_") }, "the diary was not consulted at all")
    }

    @Test
    fun `an offer that crosses a shift she already has is a conflict`() {
        // Hers runs 22:00 to 02:00; the offer runs 19:00 to 07:00. They share four hours.
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(agendaConflictPolicy = AgendaConflictPolicy.REJECT),
                    commitments = listOf(commitment(start = LocalTime.of(22, 0), end = LocalTime.of(2, 0))),
                ),
            )

        assertEquals(EvaluationResult.REJECTED, outcome.result)
        assertTrue(RuleReason.AGENDA_CONFLICT in outcome.reasons)
    }

    @Test
    fun `two shifts on one day that never cross are both hers to take`() {
        // A morning shift and an overnight one on the same date is ordinary in medicine.
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(agendaConflictPolicy = AgendaConflictPolicy.REJECT),
                    commitments = listOf(commitment(start = LocalTime.of(7, 0), end = LocalTime.of(13, 0))),
                ),
            )

        assertEquals(EvaluationResult.ELIGIBLE, outcome.result)
    }

    @Test
    fun `same-day mode collides on the date alone`() {
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(
                        agendaConflictPolicy = AgendaConflictPolicy.REJECT,
                        agendaConflictMode = AgendaConflictMode.SAME_DAY,
                    ),
                    commitments = listOf(commitment(start = LocalTime.of(7, 0), end = LocalTime.of(13, 0))),
                ),
            )

        assertEquals(EvaluationResult.REJECTED, outcome.result)
        assertTrue(RuleReason.AGENDA_CONFLICT in outcome.reasons)
    }

    @Test
    fun `a commitment is compared across the midnight it spans`() {
        // Hers started the day before and runs into this morning; the offer starts at 06:00. The
        // two share an hour that neither date alone would reveal.
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(agendaConflictPolicy = AgendaConflictPolicy.REJECT),
                    opportunity(startTime = LocalTime.of(6, 0), endTime = LocalTime.of(12, 0), endsNextDay = false),
                    commitments =
                        listOf(
                            commitment(
                                date = LocalDate.of(2026, 8, 24),
                                start = LocalTime.of(19, 0),
                                end = LocalTime.of(7, 0),
                                endsNextDay = true,
                            ),
                        ),
                ),
            )

        assertEquals(EvaluationResult.REJECTED, outcome.result)
        assertTrue(RuleReason.AGENDA_CONFLICT in outcome.reasons)
    }

    @Test
    fun `a shift that ends exactly when the next begins is not a collision`() {
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(agendaConflictPolicy = AgendaConflictPolicy.REJECT),
                    commitments = listOf(commitment(start = LocalTime.of(7, 0), end = LocalTime.of(19, 0))),
                ),
            )

        assertEquals(EvaluationResult.ELIGIBLE, outcome.result, "19:00 to 19:00 is a handover, not an overlap")
    }

    @Test
    fun `an unreadable window is uncertainty, not permission`() {
        // She wrote down a shift without hours. Not knowing whether they cross is not the same as
        // knowing they do not, so this asks rather than approves.
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(agendaConflictPolicy = AgendaConflictPolicy.REJECT),
                    commitments = listOf(commitment(start = null, end = null)),
                ),
            )

        assertEquals(EvaluationResult.REVIEW_REQUIRED, outcome.result)
        assertTrue(RuleReason.AGENDA_CONFLICT_UNCERTAIN in outcome.reasons)
    }

    @Test
    fun `an undated commitment on a neighbouring day says nothing about this one`() {
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(agendaConflictPolicy = AgendaConflictPolicy.REJECT),
                    commitments = listOf(commitment(date = LocalDate.of(2026, 8, 24), start = null, end = null)),
                ),
            )

        assertEquals(EvaluationResult.ELIGIBLE, outcome.result)
    }

    @Test
    fun `the review policy hands the collision over instead of discarding it`() {
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(agendaConflictPolicy = AgendaConflictPolicy.REVIEW),
                    commitments = listOf(commitment(start = LocalTime.of(22, 0), end = LocalTime.of(2, 0))),
                ),
            )

        assertEquals(EvaluationResult.REVIEW_REQUIRED, outcome.result)
        assertTrue(RuleReason.AGENDA_CONFLICT in outcome.reasons)
    }

    @Test
    fun `without a date the agenda rule cannot run at all`() {
        val outcome =
            engine.evaluate(
                context(
                    RuleDefinition(agendaConflictPolicy = AgendaConflictPolicy.REJECT),
                    opportunity(shiftDate = null),
                    commitments = listOf(commitment()),
                ),
            )

        assertEquals(EvaluationResult.REVIEW_REQUIRED, outcome.result)
        assertTrue(RuleReason.REQUIRED_FIELD_MISSING in outcome.reasons)
    }

    private fun context(
        definition: RuleDefinition,
        opportunity: ShiftOpportunity = opportunity(),
        groupEnabled: Boolean = true,
        groupAutoClaimEnabled: Boolean = false,
        instanceOperational: Boolean? = true,
        messageTimestamp: Instant = now.minusSeconds(60),
        commitments: List<Commitment> = emptyList(),
    ): EvaluationContext =
        EvaluationContext(
            opportunity = opportunity,
            definition = definition,
            groupEnabled = groupEnabled,
            groupAutoClaimEnabled = groupAutoClaimEnabled,
            messageTimestamp = messageTimestamp,
            instanceOperational = instanceOperational,
            now = now,
            timezone = ZoneId.of("America/Sao_Paulo"),
            commitments = commitments,
        )

    /** Something she is already committed to, on the same night as the baseline offer by default. */
    private fun commitment(
        date: LocalDate = LocalDate.of(2026, 8, 25),
        start: LocalTime? = LocalTime.of(19, 0),
        end: LocalTime? = LocalTime.of(7, 0),
        // False by default even for the overnight baseline: an end that does not follow its start
        // already says the shift ran past midnight, and the flag only has to carry the case where
        // the hours alone cannot say so.
        endsNextDay: Boolean = false,
    ): Commitment =
        Commitment(
            source = CommitmentSource.MANUAL,
            reference = UUID.randomUUID().toString(),
            label = "Santa Casa",
            shiftDate = date,
            startTime = start,
            endTime = end,
            endsNextDay = endsNextDay,
        )

    /** A complete, unambiguous overnight offer on a Tuesday: the baseline every case varies from. */
    private fun opportunity(
        ambiguousFields: List<String> = emptyList(),
        confidence: BigDecimal? = BigDecimal.ONE,
        city: String? = "Bauru",
        extractionMethod: ExtractionMethod = ExtractionMethod.DETERMINISTIC,
        shiftDate: LocalDate? = LocalDate.of(2026, 8, 25),
        startTime: LocalTime? = LocalTime.of(19, 0),
        endTime: LocalTime? = LocalTime.of(7, 0),
        endsNextDay: Boolean = true,
    ): ShiftOpportunity =
        ShiftOpportunity(
            id = UUID.randomUUID(),
            sourceMessageId = UUID.randomUUID(),
            groupId = UUID.randomUUID(),
            status = OpportunityStatus.EVALUATING,
            shiftDate = shiftDate,
            startTime = startTime,
            endTime = endTime,
            endsNextDay = endsNextDay,
            location = "PS Central",
            city = city,
            amount = BigDecimal("1200.00"),
            currency = "BRL",
            specialty = null,
            notes = null,
            extractionMethod = extractionMethod,
            confidence = confidence,
            ambiguousFields = ambiguousFields,
            resolutionReason = null,
            reviewNote = null,
            version = 0,
            detectedAt = now,
            extractionCompletedAt = now,
        )
}
