package br.com.shiftcatcher.extraction

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.foundation.text.TextNormalizer
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Stage 2 of `04-Domain/Detection-and-Extraction.md`: deterministic parsing only. The rule that
 * governs every branch here is "não inventar ausente" — anything this parser cannot read from the
 * text stays null and is reported as ambiguous, which is what later blocks an automatic claim.
 */
@Component
class ShiftExtractor(
    private val properties: ShiftCatcherProperties,
) {
    fun extract(
        text: String,
        messageTimestamp: Instant,
    ): ExtractedShift {
        val normalized = TextNormalizer.normalize(text)
        val messageDate = messageTimestamp.atZone(properties.detection.timezone).toLocalDate()

        val shiftDate = parseDate(normalized, messageDate)
        val range = parseTimeRange(normalized)
        // Only consulted when there is no explicit range: "das 7 as 19" already answers both ends.
        val clockStart = if (range == null) parseClockTime(normalized) else null
        val durationHours = if (range == null) parseDuration(normalized) else null
        val startTime = range?.start ?: clockStart
        val endTime =
            range?.end
                ?: startTime?.let { start -> durationHours?.let { start.plusHours(it.toLong()) } }
        val endsNextDay =
            when {
                range != null -> range.endsNextDay
                startTime != null && endTime != null -> !endTime.isAfter(startTime)
                else -> false
            }
        val amount = parseAmount(normalized)
        val location = TextNormalizer.matchesIn(normalized, properties.detection.knownLocations).firstOrNull()
        val city = TextNormalizer.matchesIn(normalized, properties.detection.knownCities).firstOrNull()

        val ambiguous = mutableListOf<String>()
        if (shiftDate == null) ambiguous += "shiftDate"
        // Whatever could not be pinned to a concrete time is reported, because the opportunity
        // stores times and not durations: an unreported null reads downstream as an answered one.
        if (startTime == null) ambiguous += "startTime"
        if (endTime == null) ambiguous += "endTime"

        return ExtractedShift(
            shiftDate = shiftDate,
            startTime = startTime,
            endTime = endTime,
            endsNextDay = endsNextDay,
            durationHours = durationHours,
            location = location,
            city = city,
            amount = amount,
            currency = amount?.let { "BRL" },
            specialty = null,
            notes = null,
            ambiguousFields = ambiguous.toList(),
            confidence = confidenceFor(ambiguous.size),
        )
    }

    /** Relative days resolve against the message's own timestamp in the configured timezone. */
    private fun parseDate(
        normalized: String,
        messageDate: LocalDate,
    ): LocalDate? {
        DAY_AFTER_TOMORROW.find(normalized)?.let { return messageDate.plusDays(2) }
        if (TOMORROW.containsMatchIn(normalized)) return messageDate.plusDays(1)
        if (TODAY.containsMatchIn(normalized)) return messageDate

        EXPLICIT_DATE.find(normalized)?.let { match ->
            val day = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val year = match.groupValues[3].takeIf { it.isNotBlank() }?.let(::normalizeYear) ?: messageDate.year
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }

        DAY_ONLY.find(normalized)?.let { match ->
            val day = match.groupValues[1].toInt()
            val candidate = runCatching { messageDate.withDayOfMonth(day) }.getOrNull() ?: return null
            // "dia 3" said on the 28th means next month, not a date in the past.
            return if (candidate.isBefore(messageDate)) candidate.plusMonths(1) else candidate
        }
        return null
    }

    private fun normalizeYear(raw: String): Int {
        val year = raw.toInt()
        return if (year < 100) 2000 + year else year
    }

    private fun parseTimeRange(normalized: String): TimeRange? {
        val match = TIME_RANGE.find(normalized) ?: return null
        val start = timeOf(match.groupValues[1], match.groupValues[2]) ?: return null
        val end = timeOf(match.groupValues[3], match.groupValues[4]) ?: return null
        return TimeRange(start = start, end = end, endsNextDay = !end.isAfter(start))
    }

    private fun timeOf(
        hour: String,
        minute: String,
    ): LocalTime? {
        val hourValue = hour.toIntOrNull() ?: return null
        val minuteValue = minute.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        return runCatching { LocalTime.of(hourValue, minuteValue) }.getOrNull()
    }

    /** `as 18h`, `das 19:30` — a clock reading, which is a start, not a length. */
    private fun parseClockTime(normalized: String): LocalTime? {
        val match = CLOCK_TIME.find(normalized) ?: return null
        return timeOf(match.groupValues[1], match.groupValues[2])
    }

    /**
     * A duration is only read when the text marks it as one: `de 12h`, `por 12h`, `12 horas`. A bare
     * `12h` is genuinely ambiguous between "noon" and "twelve hours long", so nothing is read from it.
     */
    private fun parseDuration(normalized: String): Int? {
        val match = DURATION_PREFIXED.find(normalized) ?: DURATION_SPELLED.find(normalized) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..24 }
    }

    private fun parseAmount(normalized: String): BigDecimal? {
        THOUSANDS_SHORTHAND.find(normalized)?.let { match ->
            val value = match.groupValues[1].replace(",", ".").toBigDecimalOrNull() ?: return@let
            return value.multiply(BigDecimal(1000)).setScale(2, RoundingMode.HALF_UP)
        }
        // An amount is only read when the text says so: a bare number is far more likely to be an
        // hour or a day than a fee, and inventing a value here would feed a wrong claim later.
        val match =
            CURRENCY_PREFIXED.find(normalized)
                ?: CURRENCY_SUFFIXED.find(normalized)
                ?: return null
        return parseBrazilianNumber(match.groupValues[1])
    }

    /** `1.200,50` is one thousand two hundred; `1200.50` and `1200,50` are the same number too. */
    private fun parseBrazilianNumber(raw: String): BigDecimal? {
        val cleaned = raw.trim().removeSuffix(".").removeSuffix(",")
        val hasComma = cleaned.contains(',')
        val normalizedNumber =
            if (hasComma) {
                cleaned.replace(".", "").replace(',', '.')
            } else if (cleaned.count { it == '.' } == 1 && cleaned.substringAfterLast('.').length == 3) {
                cleaned.replace(".", "")
            } else {
                cleaned
            }
        return normalizedNumber.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
    }

    private fun confidenceFor(ambiguousCount: Int): BigDecimal =
        BigDecimal.ONE
            .subtract(BigDecimal("0.25").multiply(BigDecimal(ambiguousCount)))
            .max(BigDecimal.ZERO)
            .setScale(4, RoundingMode.HALF_UP)

    private data class TimeRange(
        val start: LocalTime,
        val end: LocalTime,
        val endsNextDay: Boolean,
    )

    private companion object {
        val TODAY = Regex("\\bhoje\\b")
        val TOMORROW = Regex("\\bamanha\\b")
        val DAY_AFTER_TOMORROW = Regex("\\bdepois de amanha\\b")
        val EXPLICIT_DATE = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")
        val DAY_ONLY = Regex("\\bdia\\s+(\\d{1,2})\\b")

        // `/` is deliberately not a range separator: `19/07` is a date, not 19:00-07:00.
        val TIME_RANGE =
            Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*h?\\s*(?:-|ate|as|a)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*h?\\b")
        val CLOCK_TIME = Regex("\\b(?:as|das|apartir das|partir das)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*h?\\b")
        val DURATION_PREFIXED = Regex("\\b(?:de|por|durante)\\s*(\\d{1,2})\\s*h(?:oras?)?\\b")
        val DURATION_SPELLED = Regex("\\b(\\d{1,2})\\s*horas\\b")
        val THOUSANDS_SHORTHAND = Regex("\\b(\\d+(?:[.,]\\d+)?)\\s*k\\b")
        val CURRENCY_PREFIXED = Regex("r\\$\\s*(\\d[\\d.,]*)")
        val CURRENCY_SUFFIXED = Regex("\\b(\\d[\\d.,]*)\\s*reais\\b")
    }
}

data class ExtractedShift(
    val shiftDate: LocalDate?,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val endsNextDay: Boolean,
    val durationHours: Int?,
    val location: String?,
    val city: String?,
    val amount: BigDecimal?,
    val currency: String?,
    val specialty: String?,
    val notes: String?,
    val ambiguousFields: List<String>,
    val confidence: BigDecimal,
)
