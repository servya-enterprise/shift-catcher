package br.com.shiftcatcher.console

import java.math.BigDecimal

/**
 * Reads a money amount the way a Brazilian types one, in one place.
 *
 * There were three copies of this rule and they disagreed. The JSON front door read "1.800" as one
 * point eight; the server-rendered console read "1.800,00" as an unparseable string and showed her
 * the JDK's English complaint about decimal points; and both differed from the extractor that reads
 * the same numbers out of WhatsApp messages. A shift priced at eighteen hundred was stored as one
 * real eighty and then rejected by the rules for being below the minimum — which is a shift she
 * never saw, caused by a form she filled in correctly.
 *
 * Four shapes are accepted and everything else is refused:
 *
 *   1.800,00   dots group thousands, the comma is the decimal mark
 *   1800,00    no grouping, comma decimal
 *   1.800      dot-grouped thousands with no decimal part — the shape that used to become 1.8
 *   1200.50    a plain number, with or without a dot decimal
 *
 * Refusal is the point of the strictness. "1,800.00" is the US spelling and used to slip through
 * the comma branch as one real eighty with no error at all; "+1.800" used to take a different
 * branch from "1.800" and store a thousandth of it. Both now fail loudly, and a message that says
 * what to type is better than a number that is quietly wrong by a factor of a thousand.
 *
 * The bounds are the column's, not a guess: `amount numeric(12, 2) check (amount is null or amount
 * >= 0)`. Values outside them used to reach the UPDATE and come back as a 500; they are a 400 here,
 * which is what the caller can actually act on.
 *
 * NOT shared with `ShiftExtractor.parseBrazilianNumber` yet, deliberately. That one reads free text
 * a stranger wrote rather than a field a person filled in, and tightening it would change what the
 * detection pipeline extracts — which is measured against the corpus owned by `WP-POC-008`. The two
 * still disagree on "1500.000" and "1.234.567"; unifying them belongs to that work package, with
 * its corpus re-measured, not to a frontend fix.
 */
internal object BrazilianAmount {
    /** "1.800,00" and "1800,00" — grouped or not, always two decimal places at most. */
    private val WITH_CENTS = Regex("""^(\d{1,3}(\.\d{3})*|\d+),\d{1,2}$""")

    /** "1.800", "1.800.000" — bounded at four groups, which is already past the column's range. */
    private val GROUPED = Regex("""^\d{1,3}(\.\d{3}){1,3}$""")

    /** "1800", "1200.50" — a plain number with a dot decimal. */
    private val PLAIN = Regex("""^\d+(\.\d{1,2})?$""")

    /** numeric(12, 2): twelve significant digits, two of them after the point. */
    private const val MAX_INTEGER_DIGITS = 10

    private const val COMPLAINT = "amount must be a number, for example 1800 or 1.800,00"

    /**
     * Blank is absent, not zero.
     *
     * The service treats a null field as "keep what was already read", so a cleared input must
     * arrive as null rather than as a value.
     */
    fun parse(raw: String?): BigDecimal? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val normalized =
            when {
                WITH_CENTS.matches(trimmed) -> trimmed.replace(".", "").replace(',', '.')
                GROUPED.matches(trimmed) -> trimmed.replace(".", "")
                PLAIN.matches(trimmed) -> trimmed
                else -> throw IllegalArgumentException(COMPLAINT)
            }

        val value = normalized.toBigDecimalOrNull() ?: throw IllegalArgumentException(COMPLAINT)
        require(value.precision() - value.scale() <= MAX_INTEGER_DIGITS) {
            "amount is larger than this system can store"
        }
        return value
    }
}
