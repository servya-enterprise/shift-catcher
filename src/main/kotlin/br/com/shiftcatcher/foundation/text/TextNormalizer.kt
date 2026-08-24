package br.com.shiftcatcher.foundation.text

import java.text.Normalizer

/**
 * Shared by the detection and extraction stages so both match the same way. It lives in
 * `foundation` because `02-Architecture/Module-Map.md` forbids a cycle between those two modules.
 */
object TextNormalizer {
    private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")

    /** Lowercases and strips accents, so `plantão` and `plantao` compare equal. */
    fun normalize(text: String): String =
        Normalizer
            .normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")

    fun matchesIn(
        normalizedText: String,
        candidates: List<String>,
    ): List<String> = candidates.filter { normalizedText.contains(normalize(it)) }
}
