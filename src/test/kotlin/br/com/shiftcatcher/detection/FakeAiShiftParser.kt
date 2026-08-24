package br.com.shiftcatcher.detection

import br.com.shiftcatcher.ai.AiParseRequest
import br.com.shiftcatcher.ai.AiParseResult
import br.com.shiftcatcher.ai.AiShiftParserPort
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scriptable stand-in for the model. CI must never depend on a real provider, and the interesting
 * assertions are about *when* the port is consulted, not about model quality.
 */
class FakeAiShiftParser : AiShiftParserPort {
    val calls = AtomicInteger()

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var response: AiParseResult? = null

    @Volatile
    var failure: RuntimeException? = null

    override fun isEnabled(): Boolean = enabled

    override fun parse(request: AiParseRequest): AiParseResult {
        calls.incrementAndGet()
        failure?.let { throw it }
        return response ?: throw IllegalStateException("FakeAiShiftParser has no scripted response")
    }

    fun reset() {
        calls.set(0)
        enabled = false
        response = null
        failure = null
    }
}
