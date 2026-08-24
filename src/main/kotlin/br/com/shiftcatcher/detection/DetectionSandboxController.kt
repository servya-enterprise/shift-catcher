package br.com.shiftcatcher.detection

import br.com.shiftcatcher.extraction.ExtractedShift
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

/**
 * `EP-034`: runs the same detector and extractor against arbitrary text without touching the
 * database, so the fixture corpus can be tuned without replaying webhooks.
 */
@RestController
@RequestMapping("/api/v1/poc/detect")
class DetectionSandboxController(
    private val analysisService: MessageAnalysisService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @PostMapping
    fun detect(
        @RequestBody request: DetectRequest,
    ): DetectResponse {
        val text = request.text?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("text is required")
        val messageTimestamp =
            request.messageTimestamp
                ?.let { raw ->
                    runCatching {
                        Instant.parse(
                            raw,
                        )
                    }.getOrElse { throw IllegalArgumentException("messageTimestamp must be an ISO instant") }
                }
                ?: clock.instant()

        val preview = analysisService.preview(text, messageTimestamp)
        return DetectResponse(
            candidate = preview.detection.candidate,
            score = preview.detection.score,
            signals = preview.detection.signals.map { it.name },
            extraction = preview.extraction,
            ambiguousFields = preview.resolved?.ambiguousFields ?: emptyList(),
            extractionMethod = preview.resolved?.method?.name,
            aiInvoked = preview.resolved?.aiInvoked ?: false,
            persisted = false,
        )
    }
}

data class DetectRequest(
    val text: String? = null,
    val messageTimestamp: String? = null,
)

data class DetectResponse(
    val candidate: Boolean,
    val score: BigDecimal,
    val signals: List<String>,
    val extraction: ExtractedShift?,
    val ambiguousFields: List<String>,
    val extractionMethod: String?,
    val aiInvoked: Boolean,
    val persisted: Boolean,
)
