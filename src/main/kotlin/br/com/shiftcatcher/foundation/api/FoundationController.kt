package br.com.shiftcatcher.foundation.api

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
class FoundationController(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: ShiftCatcherProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @GetMapping("/health")
    fun health(): HealthResponse {
        val databaseUp = jdbcTemplate.queryForObject("select 1", Int::class.java) == 1
        return HealthResponse(
            status = if (databaseUp) "UP" else "DOWN",
            database = if (databaseUp) "UP" else "DOWN",
            timestamp = clock.instant(),
        )
    }

    @GetMapping("/poc/status")
    fun pocStatus(): PocStatusResponse =
        PocStatusResponse(
            status = "DETECTION_IN_PROGRESS",
            stage = properties.poc.stage,
            currentWorkPackage = "WP-POC-004",
            greenApiTransport = "VERIFIED",
            timestamp = clock.instant(),
        )
}

data class HealthResponse(
    val status: String,
    val database: String,
    val timestamp: Instant,
)

data class PocStatusResponse(
    val status: String,
    val stage: String,
    val currentWorkPackage: String,
    val greenApiTransport: String,
    val timestamp: Instant,
)
