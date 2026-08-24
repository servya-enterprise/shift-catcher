package br.com.shiftcatcher.ai

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The prompt is a contract with the model, so the parts that make it answer correctly are pinned
 * here rather than left to whoever edits the string next.
 */
class ShiftParserPromptTest {
    private val today = LocalDate.of(2026, 8, 24)

    @Test
    fun `the reference date and timezone are stated, because amanha means nothing without them`() {
        val prompt = ShiftParserPrompt.build("plantao amanha", today, "America/Sao_Paulo", emptyList())

        assertTrue(prompt.contains("2026-08-24"))
        assertTrue(prompt.contains("America/Sao_Paulo"))
        assertTrue(prompt.contains("2026-08-25"), "the worked example resolves against the same date")
    }

    @Test
    fun `known locations are offered so the model recognises places instead of inventing them`() {
        val prompt = ShiftParserPrompt.build("plantao", today, "America/Sao_Paulo", listOf("UPA Norte", "PS Central"))

        assertTrue(prompt.contains("UPA Norte"))
        assertTrue(prompt.contains("PS Central"))

        val without = ShiftParserPrompt.build("plantao", today, "America/Sao_Paulo", emptyList())
        assertTrue(without.contains("Nenhum local conhecido"))
    }

    @Test
    fun `the message is quoted safely so it cannot close the prompt's own quoting`() {
        val prompt = ShiftParserPrompt.build("""plantao "urgente" amanha""", today, "America/Sao_Paulo", emptyList())

        assertTrue(prompt.contains("plantao 'urgente' amanha"))
    }

    @Test
    fun `the schema forces every field to be answered, with null as the way to say unknown`() {
        @Suppress("UNCHECKED_CAST")
        val required = ShiftParserPrompt.SCHEMA["required"] as List<String>

        @Suppress("UNCHECKED_CAST")
        val properties = ShiftParserPrompt.SCHEMA["properties"] as Map<String, Any>

        assertEquals(properties.keys, required.toSet())
        assertTrue("isShiftOffer" in required)
        assertTrue("confidence" in required)

        @Suppress("UNCHECKED_CAST")
        val dateType = (properties["date"] as Map<String, Any>)["type"] as List<String>
        assertTrue("null" in dateType, "absent must be expressible without breaking the schema")
    }

    @Test
    fun `the instructions separate a clock reading from a length`() {
        val prompt = ShiftParserPrompt.build("plantao", today, "America/Sao_Paulo", emptyList())

        // The distinction the deterministic parser got wrong is stated explicitly for the model.
        assertTrue(prompt.contains("durationHours"))
        assertTrue(prompt.contains("plantao de 12h"))
        assertTrue(prompt.contains("NUNCA invente"))
    }
}
