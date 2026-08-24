package br.com.shiftcatcher.observability

import br.com.shiftcatcher.reliability.ProviderHealthGate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

/**
 * `EP-003`. Reports the observed provider state from the stored observation rather than calling the
 * provider: reading metrics must not consume the rate-limited `getStateInstance` quota.
 */
@RestController
@RequestMapping("/api/v1/metrics/latency")
class LatencyMetricsController(
    private val repository: LatencyMetricsRepository,
    private val providerHealth: ProviderHealthGate,
    private val clock: Clock = Clock.systemUTC(),
) {
    @GetMapping
    fun latency(): LatencyMetricsResponse {
        val observation = providerHealth.current()
        return LatencyMetricsResponse(
            providerToWebhook = repository.providerToWebhook(),
            detection = repository.detection(),
            decision = repository.decision(),
            sendRequest = repository.sendRequest(),
            internalClaim = repository.internalClaim(),
            counters = repository.counters(),
            providerState = observation?.state ?: "UNOBSERVED",
            providerOperational = observation?.operational ?: false,
            providerObservedAt = observation?.observedAt,
            providerObservationFresh = observation?.let { providerHealth.isFresh(it) } ?: false,
            generatedAt = clock.instant(),
        )
    }
}

data class LatencyMetricsResponse(
    val providerToWebhook: LatencySample,
    val detection: LatencySample,
    val decision: LatencySample,
    val sendRequest: LatencySample,
    val internalClaim: LatencySample,
    val counters: PipelineCounters,
    val providerState: String,
    val providerOperational: Boolean,
    val providerObservedAt: Instant?,
    val providerObservationFresh: Boolean,
    val generatedAt: Instant,
)
