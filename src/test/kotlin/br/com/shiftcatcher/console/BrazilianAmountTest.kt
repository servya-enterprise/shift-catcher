package br.com.shiftcatcher.console

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The one reader, pinned against the three that disagreed.
 *
 * Every case here was a real divergence between the JSON front door, the server-rendered console
 * and — in two rows — the extractor. A shift priced at eighteen hundred was stored as one real
 * eighty and then rejected by the rules for being below the minimum: a shift she never saw, caused
 * by a form she filled in correctly.
 */
class BrazilianAmountTest {
    @Test
    fun `reads the four shapes a person actually types`() {
        assertEquals(BigDecimal("1800.00"), BrazilianAmount.parse("1.800,00"))
        assertEquals(BigDecimal("1800.00"), BrazilianAmount.parse("1800,00"))
        // The one that used to become 1.8 in the JSON door and in the server-rendered console.
        assertEquals(BigDecimal("1800"), BrazilianAmount.parse("1.800"))
        assertEquals(BigDecimal("1800"), BrazilianAmount.parse("1800"))
        assertEquals(BigDecimal("1200.50"), BrazilianAmount.parse("1200.50"))
        assertEquals(BigDecimal("1000000"), BrazilianAmount.parse("1.000.000"))
        // A dot with one or two digits after it is a decimal point, not a broken thousands group:
        // somebody typing "1.80" means one real eighty, and "1.800" means eighteen hundred. The
        // difference is the length of the tail, which is exactly what the patterns key off.
        assertEquals(BigDecimal("1.80"), BrazilianAmount.parse("1.80"))
    }

    @Test
    fun `refuses the US spelling instead of dividing it by a thousand`() {
        // "1,800.00" used to take the comma branch and come out as 1.80000 — one real eighty, with
        // no exception at all, because the old guard only fired on a string BigDecimal could not
        // read. A refusal she can act on beats a number that is silently wrong.
        assertFailsWith<IllegalArgumentException> { BrazilianAmount.parse("1,800.00") }
    }

    @Test
    fun `refuses a sign rather than letting two spellings disagree`() {
        // The old pattern spelled `-?` but not `+`, so "+1.800" fell to a different branch from
        // "1.800" and stored a thousandth of it. And the column is `check (amount >= 0)`, so a
        // negative used to reach the UPDATE and come back as a 500 instead of a 400.
        assertFailsWith<IllegalArgumentException> { BrazilianAmount.parse("+1.800") }
        assertFailsWith<IllegalArgumentException> { BrazilianAmount.parse("-1.800") }
    }

    @Test
    fun `refuses what the column cannot hold, as a request problem rather than a server error`() {
        // amount is numeric(12, 2): ten integer digits. Eleven used to be parsed happily, reach the
        // UPDATE, and come back through the catch-all handler as 500 INTERNAL_ERROR.
        assertFailsWith<IllegalArgumentException> { BrazilianAmount.parse("12.345.678.901") }
    }

    @Test
    fun `refuses the shapes that are not numbers at all`() {
        for (raw in listOf("mil e oitocentos", "R$ 1.800", "1..800", "1.8000", "1,8,0", "abc")) {
            assertFailsWith<IllegalArgumentException>("should have refused: $raw") {
                BrazilianAmount.parse(raw)
            }
        }
    }

    @Test
    fun `blank is absent, never zero`() {
        // The service reads a null field as "keep what was already extracted", so a cleared input
        // has to arrive as null. Zero would erase a real amount with a fabricated one.
        assertNull(BrazilianAmount.parse(null))
        assertNull(BrazilianAmount.parse(""))
        assertNull(BrazilianAmount.parse("   "))
    }
}
