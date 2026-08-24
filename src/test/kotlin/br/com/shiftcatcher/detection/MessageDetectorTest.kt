package br.com.shiftcatcher.detection

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fixture corpus for the stage 1 filter. The cases are written the way the groups actually talk, so
 * a regression here means real offers stop being seen or ordinary chatter starts waking the parser.
 */
class MessageDetectorTest {
    private val detector =
        MessageDetector(
            ShiftCatcherProperties(
                detection =
                    ShiftCatcherProperties.Detection(
                        knownLocations = listOf("PS Central", "Hospital Regional"),
                        knownCities = listOf("Bauru"),
                    ),
            ),
        )

    @Test
    fun `shift offers are candidates`() {
        val offers =
            listOf(
                "Plantão amanhã 19-07 no PS Central R$ 1.200",
                "plantao 07 as 19 sabado",
                "vaga para cobrir escala de domingo",
                "Cobertura 12h hoje, alguém?",
                "troco plantão de sexta por sábado",
                "19-07 R$1200",
                "PLANTAO AMANHA",
            )
        offers.forEach { text ->
            val outcome = detector.detect(text)
            assertTrue(outcome.candidate, "expected candidate: $text (score=${outcome.score})")
        }
    }

    @Test
    fun `ordinary conversation never reaches the parser`() {
        val chatter =
            listOf(
                "bom dia pessoal",
                "alguém viu meu estetoscópio?",
                "obrigado gente, ótimo fim de semana",
                "vamos marcar o almoço 12h?",
                "o café da copa acabou",
            )
        chatter.forEach { text ->
            val outcome = detector.detect(text)
            assertTrue(!outcome.candidate, "expected non-candidate: $text (score=${outcome.score})")
        }
    }

    @Test
    fun `accents and casing do not change the decision`() {
        assertEquals(detector.detect("plantão").candidate, detector.detect("PLANTAO").candidate)
        assertTrue(detector.detect("Plantão").candidate)
    }

    @Test
    fun `signals are reported so a decision can be explained`() {
        val outcome = detector.detect("plantão amanhã 19-07 no PS Central R$ 1.200")

        assertTrue(DetectionSignal.SHIFT_KEYWORD in outcome.signals)
        assertTrue(DetectionSignal.TIME_RANGE in outcome.signals)
        assertTrue(DetectionSignal.AMOUNT in outcome.signals)
        assertTrue(DetectionSignal.KNOWN_LOCATION in outcome.signals)
        assertEquals(0, outcome.score.compareTo(java.math.BigDecimal.ONE), "capped at 1")
    }

    @Test
    fun `a time range plus a fee is enough without any keyword`() {
        val outcome = detector.detect("19-07 R$1200")

        assertTrue(outcome.candidate)
        assertTrue(DetectionSignal.SHIFT_KEYWORD !in outcome.signals)
    }
}
