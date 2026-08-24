package br.com.shiftcatcher.detection

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.foundation.text.TextNormalizer
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Stage 1 of `04-Domain/Detection-and-Extraction.md`: a cheap, deterministic filter that decides
 * whether a message is worth parsing. `DEC-004` makes this the gate that keeps the AI parser away
 * from ordinary group conversation.
 */
@Component
class MessageDetector(
    private val properties: ShiftCatcherProperties,
) {
    fun detect(text: String): DetectionOutcome {
        val normalized = TextNormalizer.normalize(text)
        val signals = mutableListOf<DetectionSignal>()

        if (SHIFT_KEYWORDS.any { normalized.contains(it) }) {
            signals += DetectionSignal.SHIFT_KEYWORD
        }
        if (TIME_RANGE.containsMatchIn(normalized)) {
            signals += DetectionSignal.TIME_RANGE
        }
        if (DURATION.containsMatchIn(normalized)) {
            signals += DetectionSignal.DURATION
        }
        if (AMOUNT.containsMatchIn(normalized)) {
            signals += DetectionSignal.AMOUNT
        }
        if (TextNormalizer.matchesIn(normalized, properties.detection.knownLocations).isNotEmpty()) {
            signals += DetectionSignal.KNOWN_LOCATION
        }

        val score =
            signals
                .fold(BigDecimal.ZERO) { total, signal -> total + signal.weight }
                .min(BigDecimal.ONE)
                .setScale(4, RoundingMode.HALF_UP)
        return DetectionOutcome(
            candidate = score >= CANDIDATE_THRESHOLD,
            score = score,
            signals = signals.toList(),
        )
    }

    private companion object {
        private val SHIFT_KEYWORDS =
            listOf("plantao", "vaga", "cobrir", "cobertura", "troco", "troca", "escala")

        /** `19-07`, `07 as 19`, `19h as 7h`, `19:00-07:00`, `das 7 as 19`. */
        private val TIME_RANGE =
            Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*h?\\s*(?:-|as|ate)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*h?\\b")

        /** `12h`, `24 horas`, `plantao de 6h`. */
        private val DURATION = Regex("\\b(\\d{1,2})\\s*h(?:oras?)?\\b")

        /** `R$ 1.200,00`, `1.2k`, `1200 reais`. */
        private val AMOUNT = Regex("(r\\$\\s*\\d|\\b\\d+(?:[.,]\\d+)?\\s*k\\b|\\b\\d{3,}\\s*reais\\b)")

        private val CANDIDATE_THRESHOLD = BigDecimal("0.5")
    }
}

enum class DetectionSignal(
    val weight: BigDecimal,
) {
    SHIFT_KEYWORD(BigDecimal("0.5")),
    TIME_RANGE(BigDecimal("0.3")),
    AMOUNT(BigDecimal("0.2")),
    DURATION(BigDecimal("0.15")),
    KNOWN_LOCATION(BigDecimal("0.1")),
}

data class DetectionOutcome(
    val candidate: Boolean,
    val score: BigDecimal,
    val signals: List<DetectionSignal>,
)
