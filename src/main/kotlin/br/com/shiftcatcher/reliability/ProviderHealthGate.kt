package br.com.shiftcatcher.reliability

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.integration.greenapi.WhatsAppInstanceHealth
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Repository
class ProviderHealthRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun record(observation: ProviderHealthObservation) {
        jdbcTemplate.update(
            UPSERT_SQL,
            observation.state,
            observation.operational,
            Timestamp.from(observation.observedAt),
            observation.consecutiveFailures,
            observation.lastError,
        )
    }

    fun latest(): ProviderHealthObservation? = jdbcTemplate.query(SELECT_SQL, ROW_MAPPER).firstOrNull()

    private companion object {
        val SELECT_SQL =
            """
            select state, operational, observed_at, consecutive_failures, last_error
              from provider_health
             where id = 1
            """.trimIndent()

        val UPSERT_SQL =
            """
            insert into provider_health (id, state, operational, observed_at, consecutive_failures, last_error)
            values (1, ?, ?, ?, ?, ?)
            on conflict (id) do update
               set state = excluded.state,
                   operational = excluded.operational,
                   observed_at = excluded.observed_at,
                   consecutive_failures = excluded.consecutive_failures,
                   last_error = excluded.last_error,
                   updated_at = current_timestamp
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                ProviderHealthObservation(
                    state = resultSet.getString("state"),
                    operational = resultSet.getBoolean("operational"),
                    observedAt = resultSet.getTimestamp("observed_at").toInstant(),
                    consecutiveFailures = resultSet.getInt("consecutive_failures"),
                    lastError = resultSet.getString("last_error"),
                )
            }
    }
}

data class ProviderHealthObservation(
    val state: String,
    val operational: Boolean,
    val observedAt: Instant,
    val consecutiveFailures: Int,
    val lastError: String?,
)

/**
 * Answers "may we act right now?" from the most recent observation, falling back to a live call only
 * when there is nothing fresh. An observation older than the configured freshness window is treated
 * as no answer at all, which blocks the automatic path rather than trusting stale good news.
 */
@Component
class ProviderHealthGate(
    private val repository: ProviderHealthRepository,
    private val instanceHealth: WhatsAppInstanceHealth,
    private val properties: ShiftCatcherProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun isOperational(): Boolean = observe().operational

    /** Null-safe view used by the metrics endpoint and by the automatic trigger. */
    fun current(): ProviderHealthObservation? = repository.latest()

    fun isFresh(observation: ProviderHealthObservation): Boolean =
        Duration.between(observation.observedAt, clock.instant()) <=
            Duration.ofSeconds(properties.claim.healthFreshnessSeconds)

    private fun observe(): ProviderHealthObservation {
        repository.latest()?.takeIf { isFresh(it) }?.let { return it }
        return refresh()
    }

    /** Called on a schedule and whenever the cached observation has gone stale. */
    fun refresh(): ProviderHealthObservation {
        val previous = repository.latest()
        val observation =
            runCatching { instanceHealth.getState() }
                .fold(
                    onSuccess = { health ->
                        ProviderHealthObservation(
                            state = health.state.name,
                            operational = health.operational,
                            observedAt = clock.instant(),
                            consecutiveFailures = 0,
                            lastError = null,
                        )
                    },
                    onFailure = { failure ->
                        logger.warn("Could not observe the provider state", failure)
                        ProviderHealthObservation(
                            state = "UNKNOWN",
                            // Not knowing is never permission to act.
                            operational = false,
                            observedAt = clock.instant(),
                            consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                            lastError = failure.message?.take(256),
                        )
                    },
                )
        repository.record(observation)
        return observation
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ProviderHealthGate::class.java)
    }
}

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    prefix = "shift-catcher.claim",
    name = ["worker-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ProviderHealthMonitor(
    private val gate: ProviderHealthGate,
) {
    @Scheduled(fixedDelayString = "\${shift-catcher.claim.health-interval-ms:60000}")
    fun observe() {
        gate.refresh()
    }
}
