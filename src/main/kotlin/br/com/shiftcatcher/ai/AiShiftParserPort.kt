package br.com.shiftcatcher.ai

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * `07-AI/AI-Parser-Contract.md`: the model interprets text, it never decides a claim. Keeping the
 * provider behind this port is what lets the rest of the pipeline stay deterministic and testable.
 */
interface AiShiftParserPort {
    fun isEnabled(): Boolean

    fun parse(request: AiParseRequest): AiParseResult
}

data class AiParseRequest(
    val text: String,
    val messageTimestamp: Instant,
    val timezone: ZoneId,
    val knownLocations: List<String>,
)

data class AiParseResult(
    val isShiftOffer: Boolean,
    val confidence: BigDecimal?,
    val date: LocalDate?,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val durationHours: Int?,
    val location: String?,
    val city: String?,
    val amount: BigDecimal?,
    val currency: String?,
    val specialty: String?,
    val notes: String?,
    val ambiguousFields: List<String>,
) {
    /**
     * The contract requires a schema-valid answer; anything outside these bounds is treated as an
     * invalid response and sent to review rather than trusted.
     */
    fun isSchemaValid(): Boolean {
        val confidenceInRange =
            confidence == null || (confidence >= BigDecimal.ZERO && confidence <= BigDecimal.ONE)
        val amountInRange = amount == null || amount >= BigDecimal.ZERO
        val currencyValid = currency == null || currency.length == 3
        val durationInRange = durationHours == null || durationHours in 1..24
        return confidenceInRange && amountInRange && currencyValid && durationInRange
    }
}

/**
 * The only implementation shipped with the POC. No model is called until a real adapter exists and
 * `shift-catcher.ai.enabled` is turned on, so ambiguity fails safe into review.
 */
@Component
class DisabledAiShiftParser(
    private val properties: ShiftCatcherProperties,
) : AiShiftParserPort {
    override fun isEnabled(): Boolean = properties.ai.enabled

    override fun parse(request: AiParseRequest): AiParseResult = throw IllegalStateException("No AI shift parser adapter is configured")
}
