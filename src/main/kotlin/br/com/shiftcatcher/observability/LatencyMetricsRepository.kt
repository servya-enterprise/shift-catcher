package br.com.shiftcatcher.observability

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

/**
 * The five latency metrics of `02-Architecture/Latency-SLO.md`, each computed from timestamps that
 * were recorded when the thing actually happened. Percentiles are done in the database so the
 * endpoint never loads the whole history to sort it in memory.
 */
@Repository
class LatencyMetricsRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun providerToWebhook(): LatencySample =
        percentiles(
            """
            select extract(epoch from (webhook_received_at - provider_timestamp)) * 1000 as value
              from incoming_provider_event
            """.trimIndent(),
        )

    fun detection(): LatencySample =
        percentiles(
            """
            select extract(epoch from (d.completed_at - e.webhook_received_at)) * 1000 as value
              from detection_result d
              join incoming_message m on m.id = d.message_id
              join incoming_provider_event e on e.id = m.provider_event_id
            """.trimIndent(),
        )

    fun decision(): LatencySample =
        percentiles(
            """
            select extract(epoch from (c.decided_at - e.webhook_received_at)) * 1000 as value
              from shift_claim c
              join shift_opportunity o on o.id = c.opportunity_id
              join incoming_message m on m.id = o.source_message_id
              join incoming_provider_event e on e.id = m.provider_event_id
            """.trimIndent(),
        )

    fun sendRequest(): LatencySample =
        percentiles(
            """
            select extract(epoch from (a.completed_at - c.decided_at)) * 1000 as value
              from claim_attempt a
              join shift_claim c on c.id = a.claim_id
             where a.result = 'ACCEPTED'
            """.trimIndent(),
        )

    /** The number the POC is judged on: webhook received to provider accepted. */
    fun internalClaim(): LatencySample =
        percentiles(
            """
            select extract(epoch from (a.completed_at - e.webhook_received_at)) * 1000 as value
              from claim_attempt a
              join shift_claim c on c.id = a.claim_id
              join shift_opportunity o on o.id = c.opportunity_id
              join incoming_message m on m.id = o.source_message_id
              join incoming_provider_event e on e.id = m.provider_event_id
             where a.result = 'ACCEPTED'
            """.trimIndent(),
        )

    fun counters(): PipelineCounters =
        jdbcTemplate.queryForObject(COUNTERS_SQL) { resultSet, _ ->
            PipelineCounters(
                webhooks = resultSet.getLong("webhooks"),
                duplicates = resultSet.getLong("duplicates"),
                messages = resultSet.getLong("messages"),
                candidates = resultSet.getLong("candidates"),
                opportunities = resultSet.getLong("opportunities"),
                aiFallbacks = resultSet.getLong("ai_fallbacks"),
                claims = resultSet.getLong("claims"),
                claimed = resultSet.getLong("claimed"),
                claimsFailed = resultSet.getLong("claims_failed"),
                attempts = resultSet.getLong("attempts"),
                retries = resultSet.getLong("retries"),
            )
        }!!

    private fun percentiles(valuesSql: String): LatencySample =
        jdbcTemplate.queryForObject(
            """
            select count(*) as samples,
                   percentile_cont(0.5) within group (order by value) as p50,
                   percentile_cont(0.95) within group (order by value) as p95,
                   percentile_cont(0.99) within group (order by value) as p99,
                   max(value) as worst
              from ($valuesSql) as source
             where value is not null
            """.trimIndent(),
            SAMPLE_MAPPER,
        )!!

    private companion object {
        val SAMPLE_MAPPER =
            RowMapper { resultSet, _ ->
                LatencySample(
                    samples = resultSet.getLong("samples"),
                    p50Ms = resultSet.getObject("p50")?.let { (it as Number).toDouble() },
                    p95Ms = resultSet.getObject("p95")?.let { (it as Number).toDouble() },
                    p99Ms = resultSet.getObject("p99")?.let { (it as Number).toDouble() },
                    worstMs = resultSet.getObject("worst")?.let { (it as Number).toDouble() },
                )
            }

        val COUNTERS_SQL =
            """
            select (select count(*) from incoming_provider_event) as webhooks,
                   (select coalesce(sum(duplicate_count), 0) from incoming_provider_event) as duplicates,
                   (select count(*) from incoming_message) as messages,
                   (select count(*) from detection_result where candidate) as candidates,
                   (select count(*) from shift_opportunity) as opportunities,
                   (select count(*) from shift_opportunity where extraction_method = 'AI_FALLBACK') as ai_fallbacks,
                   (select count(*) from shift_claim) as claims,
                   (select count(*) from shift_claim where status = 'CLAIMED') as claimed,
                   (select count(*) from shift_claim where status = 'FAILED') as claims_failed,
                   (select count(*) from claim_attempt) as attempts,
                   (select count(*) from claim_attempt where attempt_number > 1) as retries
            """.trimIndent()
    }
}

data class LatencySample(
    val samples: Long,
    val p50Ms: Double?,
    val p95Ms: Double?,
    val p99Ms: Double?,
    val worstMs: Double?,
)

data class PipelineCounters(
    val webhooks: Long,
    val duplicates: Long,
    val messages: Long,
    val candidates: Long,
    val opportunities: Long,
    val aiFallbacks: Long,
    val claims: Long,
    val claimed: Long,
    val claimsFailed: Long,
    val attempts: Long,
    val retries: Long,
)
