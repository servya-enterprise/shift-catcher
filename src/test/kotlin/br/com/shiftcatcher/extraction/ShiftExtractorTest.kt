package br.com.shiftcatcher.extraction

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShiftExtractorTest {
    private val extractor =
        ShiftExtractor(
            ShiftCatcherProperties(
                detection =
                    ShiftCatcherProperties.Detection(
                        knownLocations = listOf("PS Central", "Hospital Regional"),
                        knownCities = listOf("Bauru"),
                    ),
            ),
        )

    /** 2026-08-24T22:00:00Z is still the 24th in America/Sao_Paulo (19:00). */
    private val mondayEvening = Instant.parse("2026-08-24T22:00:00Z")

    @Test
    fun `relative days resolve in the configured timezone`() {
        assertEquals(LocalDate.of(2026, 8, 24), extractor.extract("plantao hoje 19-07", mondayEvening).shiftDate)
        assertEquals(LocalDate.of(2026, 8, 25), extractor.extract("plantao amanha 19-07", mondayEvening).shiftDate)
        assertEquals(
            LocalDate.of(2026, 8, 26),
            extractor.extract("plantao depois de amanha 19-07", mondayEvening).shiftDate,
        )
    }

    @Test
    fun `a late night message still resolves tomorrow against local time`() {
        // 2026-08-25T01:00:00Z is 22:00 on the 24th in Sao Paulo, so "amanha" is the 25th, not the 26th.
        val lateNight = Instant.parse("2026-08-25T01:00:00Z")

        assertEquals(LocalDate.of(2026, 8, 25), extractor.extract("plantao amanha 19-07", lateNight).shiftDate)
    }

    @Test
    fun `explicit dates are read as day first`() {
        assertEquals(LocalDate.of(2026, 12, 25), extractor.extract("plantao 25/12 19-07", mondayEvening).shiftDate)
        assertEquals(LocalDate.of(2026, 7, 19), extractor.extract("plantao 19/07/2026 07 as 19", mondayEvening).shiftDate)
    }

    @Test
    fun `a bare day number never resolves into the past`() {
        assertEquals(LocalDate.of(2026, 8, 28), extractor.extract("plantao dia 28 19-07", mondayEvening).shiftDate)
        assertEquals(LocalDate.of(2026, 9, 3), extractor.extract("plantao dia 3 19-07", mondayEvening).shiftDate)
    }

    @Test
    fun `an overnight range is flagged as ending the next day`() {
        val overnight = extractor.extract("plantao amanha 19-07", mondayEvening)

        assertEquals(LocalTime.of(19, 0), overnight.startTime)
        assertEquals(LocalTime.of(7, 0), overnight.endTime)
        assertTrue(overnight.endsNextDay)

        val daytime = extractor.extract("plantao amanha das 7 as 19", mondayEvening)
        assertEquals(LocalTime.of(7, 0), daytime.startTime)
        assertEquals(LocalTime.of(19, 0), daytime.endTime)
        assertTrue(!daytime.endsNextDay)
    }

    @Test
    fun `minutes are preserved when the message spells them out`() {
        val extracted = extractor.extract("plantao amanha 19:30 as 07:15", mondayEvening)

        assertEquals(LocalTime.of(19, 30), extracted.startTime)
        assertEquals(LocalTime.of(7, 15), extracted.endTime)
    }

    @Test
    fun `a duration without a start time leaves the start ambiguous`() {
        val extracted = extractor.extract("plantao amanha 12h", mondayEvening)

        assertEquals(12, extracted.durationHours)
        assertNull(extracted.startTime)
        assertTrue("startTime" in extracted.ambiguousFields)
    }

    @Test
    fun `amounts are read only when the text says it is money`() {
        assertEquals(BigDecimal("1200.00"), extractor.extract("plantao amanha 19-07 R$ 1.200,00", mondayEvening).amount)
        assertEquals(BigDecimal("1200.00"), extractor.extract("plantao amanha 19-07 R$1200", mondayEvening).amount)
        assertEquals(BigDecimal("1200.00"), extractor.extract("plantao amanha 19-07 1.2k", mondayEvening).amount)
        assertEquals(BigDecimal("1500.00"), extractor.extract("plantao amanha 19-07 1500 reais", mondayEvening).amount)
        assertEquals(BigDecimal("1200.50"), extractor.extract("plantao amanha 19-07 R$ 1.200,50", mondayEvening).amount)
    }

    @Test
    fun `hours in a range are never mistaken for a fee`() {
        val extracted = extractor.extract("plantao amanha 19-07 no PS Central", mondayEvening)

        assertNull(extracted.amount)
        assertNull(extracted.currency)
    }

    @Test
    fun `known locations and cities are matched accent insensitively`() {
        val extracted = extractor.extract("plantao amanha 19-07 no ps central em bauru", mondayEvening)

        assertEquals("PS Central", extracted.location)
        assertEquals("Bauru", extracted.city)
    }

    @Test
    fun `a complete offer has no ambiguity and full confidence`() {
        val extracted = extractor.extract("Plantão amanhã 19-07 no PS Central R$ 1.200", mondayEvening)

        assertTrue(extracted.ambiguousFields.isEmpty(), "unexpected: ${extracted.ambiguousFields}")
        assertEquals(0, extracted.confidence.compareTo(BigDecimal.ONE))
    }

    @Test
    fun `nothing is invented when the message only hints at an offer`() {
        val extracted = extractor.extract("tem vaga de plantao alguem quer", mondayEvening)

        assertNull(extracted.shiftDate)
        assertNull(extracted.startTime)
        assertNull(extracted.endTime)
        assertNull(extracted.amount)
        assertEquals(listOf("shiftDate", "startTime", "endTime"), extracted.ambiguousFields)
    }
}
