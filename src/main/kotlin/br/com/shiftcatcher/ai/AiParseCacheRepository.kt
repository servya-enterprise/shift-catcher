package br.com.shiftcatcher.ai

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
class AiParseCacheRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    /** Returns the stored answer and counts the hit, so the cache's value is measurable. */
    @Transactional
    fun find(
        textHash: String,
        model: String,
        referenceDate: LocalDate,
    ): String? =
        jdbcTemplate
            .query(HIT_SQL, { rs, _ -> rs.getString("response_json") }, textHash, model, referenceDate)
            .firstOrNull()

    @Transactional
    fun store(
        textHash: String,
        model: String,
        referenceDate: LocalDate,
        responseJson: String,
        latencyMs: Int,
    ) {
        jdbcTemplate.update(STORE_SQL, textHash, model, referenceDate, responseJson, latencyMs)
    }

    private companion object {
        val HIT_SQL =
            """
            update ai_parse_cache
               set hits = hits + 1,
                   last_used_at = current_timestamp
             where text_hash = ?
               and model = ?
               and reference_date = ?
            returning response_json::text as response_json
            """.trimIndent()

        val STORE_SQL =
            """
            insert into ai_parse_cache (text_hash, model, reference_date, response_json, latency_ms)
            values (?, ?, ?, ?::jsonb, ?)
            on conflict (text_hash, model, reference_date) do update
               set response_json = excluded.response_json,
                   latency_ms = excluded.latency_ms,
                   last_used_at = current_timestamp
            """.trimIndent()
    }
}
