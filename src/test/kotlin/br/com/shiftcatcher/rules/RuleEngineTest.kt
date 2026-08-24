package br.com.shiftcatcher.rules

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

    private fun context(
        definition: RuleDefinition,
        opportunity: ShiftOpportunity = opportunity(),
        groupEnabled: Boolean = true,
        groupAutoClaimEnabled: Boolean = false,
        instanceOperational: Boolean? = true,
        messageTimestamp: Instant = now.minusSeconds(60),
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
        )

    /** A complete, unambiguous overnight offer on a Tuesday: the baseline every case varies from. */
    private fun opportunity(
        ambiguousFields: List<String> = emptyList(),
        confidence: BigDecimal? = BigDecimal.ONE,
        city: String? = "Bauru",
        extractionMethod: ExtractionMethod = ExtractionMethod.DETERMINISTIC,
    ): ShiftOpportunity =
        ShiftOpportunity(
            id = UUID.randomUUID(),
            sourceMessageId = UUID.randomUUID(),
            groupId = UUID.randomUUID(),
            status = OpportunityStatus.EVALUATING,
            shiftDate = LocalDate.of(2026, 8, 25),
            startTime = LocalTime.of(19, 0),
            endTime = LocalTime.of(7, 0),
            endsNextDay = true,
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
