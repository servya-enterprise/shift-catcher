package br.com.shiftcatcher.console

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * The formatter is where a timezone mistake would live, so it is measured against a fixed clock
 * rather than exercised through a controller that reads the wall clock.
 *
 * The one thing worth stating: the reference instant below is 22:30 UTC, which is 19:30 the same
 * evening in São Paulo. A formatter that used the server's day rather than hers would call the
 * shift on the 26th "Amanhã" for three hours every night.
 */
class ConsoleFormatterTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = Clock.fixed(Instant.parse("2026-08-26T22:30:00Z"), ZoneId.of("UTC"))
    private val formatter = ConsoleFormatter(zone, clock)

    @Test
    fun `the eyebrow speaks in days she can act on, and in weekdays after that`() {
        assertEquals("Hoje", formatter.dateEyebrow(LocalDate.of(2026, 8, 26)))
        assertEquals("Amanhã", formatter.dateEyebrow(LocalDate.of(2026, 8, 27)))
        assertEquals("Ontem", formatter.dateEyebrow(LocalDate.of(2026, 8, 25)))
        // Beyond that, the weekday is what tells her whether a shift collides with something.
        assertEquals("sáb., 29/08", formatter.dateEyebrow(LocalDate.of(2026, 8, 29)))
    }

    @Test
    fun `an offer with no date says so instead of pretending`() {
        assertEquals("sem data", formatter.dateEyebrow(null))
        assertEquals("data? horário?", formatter.window(null, null, null, false))
    }

    @Test
    fun `a shift that crosses midnight is twelve hours, not minus twelve`() {
        // 19:00 to 07:00 is the ordinary night shift, and subtracting the two gives -12h.
        assertEquals("12h", formatter.duration(LocalTime.of(19, 0), LocalTime.of(7, 0), true))
        assertEquals("6h", formatter.duration(LocalTime.of(7, 0), LocalTime.of(13, 0), false))
        assertEquals("11h30", formatter.duration(LocalTime.of(19, 30), LocalTime.of(7, 0), true))
        // A window with only one end has no duration to state.
        assertEquals("", formatter.duration(LocalTime.of(19, 0), null, false))
    }

    @Test
    fun `money that is absent is an empty string, not a zero`() {
        // The screen must test for empty rather than reach for a null-coalescing default: rendering
        // "R$ 0,00" where the message never mentioned a value invents a fact.
        assertEquals("", formatter.money(null, "BRL"))
        assertEquals("R$ 1.800,00", formatter.money(BigDecimal("1800.00"), "BRL"))
    }

    @Test
    fun `the window is written the way she reads it on a phone`() {
        assertEquals(
            "25/08 19:00–07:00 (+1)",
            formatter.window(LocalDate.of(2026, 8, 25), LocalTime.of(19, 0), LocalTime.of(7, 0), true),
        )
    }
}
