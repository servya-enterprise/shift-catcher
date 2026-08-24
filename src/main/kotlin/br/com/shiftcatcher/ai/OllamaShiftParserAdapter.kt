package br.com.shiftcatcher.ai

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.foundation.text.TextNormalizer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * `07-AI/AI-Parser-Contract.md` behind a local Ollama. The model interprets; it never decides. Its
 * answer is schema-constrained by the decoder, validated again here, merged only into gaps the
 * deterministic parser left, and still judged by every hard rule downstream.
 *
 * Only registered when an adapter is actually configured, so the disabled default stays the shipped
 * behaviour rather than a runtime accident.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "shift-catcher.ai", name = ["enabled"], havingValue = "true")
class OllamaShiftParserAdapter(
    private val properties: ShiftCatcherProperties,
    private val cache: AiParseCacheRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) : AiShiftParserPort {
    private val restClient: RestClient by lazy {
        val factory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(java.time.Duration.ofSeconds(3))
                setReadTimeout(properties.ai.timeout)
            }
        RestClient.builder().requestFactory(factory).build()
    }

    override fun isEnabled(): Boolean = properties.ai.isConfigured()

    override fun parse(request: AiParseRequest): AiParseResult {
        val referenceDate = request.messageTimestamp.atZone(request.timezone).toLocalDate()
        val normalized = TextNormalizer.normalize(request.text)
        val hash = sha256(normalized)

        if (properties.ai.cacheEnabled) {
            cache.find(hash, properties.ai.model, referenceDate)?.let { cached ->
                return toResult(objectMapper.readTree(cached))
            }
        }

        val prompt =
            ShiftParserPrompt.build(
                text = request.text,
                referenceDate = referenceDate,
                timezone = request.timezone.id,
                knownLocations = request.knownLocations,
            )
        val startedAt = clock.instant()
        val body =
            restClient
                .post()
                .uri("${properties.ai.baseUrl.trimEnd('/')}/api/generate")
                .body(
                    mapOf(
                        "model" to properties.ai.model,
                        "prompt" to prompt,
                        "stream" to false,
                        "format" to ShiftParserPrompt.SCHEMA,
                        // Deterministic decoding: the same message must not read differently twice.
                        "options" to mapOf("temperature" to 0),
                    ),
                ).retrieve()
                .body(Map::class.java)
                ?: throw IllegalStateException("Ollama returned an empty body")
        val latencyMs =
            java.time.Duration
                .between(startedAt, clock.instant())
                .toMillis()
                .toInt()
        val raw =
            (body["response"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Ollama returned no response field")

        val parsed = objectMapper.readTree(raw)
        if (properties.ai.cacheEnabled) {
            runCatching { cache.store(hash, properties.ai.model, referenceDate, raw, latencyMs) }
                .onFailure { logger.warn("Could not cache the AI answer", it) }
        }
        logger.info("AI parse completed in {} ms", latencyMs)
        return toResult(parsed)
    }

    private fun toResult(node: tools.jackson.databind.JsonNode): AiParseResult =
        AiParseResult(
            isShiftOffer = node.path("isShiftOffer").asBoolean(false),
            confidence = node.decimal("confidence"),
            date = node.text("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            startTime = node.text("startTime")?.let(::parseTime),
            endTime = node.text("endTime")?.let(::parseTime),
            durationHours = node.path("durationHours").takeIf { it.isNumber }?.asInt(),
            location = node.text("location"),
            city = node.text("city"),
            amount = node.decimal("amount"),
            currency = node.decimal("amount")?.let { "BRL" },
            specialty = node.text("specialty"),
            notes = null,
            // A field the model could not fill is reported as ambiguous, exactly like the
            // deterministic parser does, so the merge downstream treats both the same way.
            ambiguousFields =
                listOfNotNull(
                    "shiftDate".takeIf { node.text("date") == null },
                    "startTime".takeIf { node.text("startTime") == null },
                    "endTime".takeIf { node.text("endTime") == null && node.path("durationHours").isNull },
                ),
        )

    /** Accepts `19:00` and `19`, which the model produces interchangeably. */
    private fun parseTime(raw: String): LocalTime? =
        runCatching { LocalTime.parse(raw) }
            .getOrElse { raw.toIntOrNull()?.let { hour -> runCatching { LocalTime.of(hour, 0) }.getOrNull() } }

    private fun tools.jackson.databind.JsonNode.text(field: String): String? =
        path(field).takeIf { !it.isNull && it.isTextual }?.asString()?.takeIf { it.isNotBlank() }

    private fun tools.jackson.databind.JsonNode.decimal(field: String): BigDecimal? =
        path(field).takeIf { it.isNumber }?.let { BigDecimal(it.asString()) }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val logger = LoggerFactory.getLogger(OllamaShiftParserAdapter::class.java)
    }
}
