package br.com.shiftcatcher.rules

import br.com.shiftcatcher.integration.greenapi.GreenApiInstanceHealth
import br.com.shiftcatcher.integration.greenapi.GreenApiInstanceState
import br.com.shiftcatcher.integration.greenapi.WhatsAppInstanceHealth
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts how often the provider is asked for its state. The provider rate-limits that call, so the
 * count is the assertion that matters, not just the answer.
 */
class FakeInstanceHealth : WhatsAppInstanceHealth {
    val calls = AtomicInteger()

    @Volatile
    var state: GreenApiInstanceState? = null

    override fun getState(): GreenApiInstanceHealth {
        calls.incrementAndGet()
        val current = state ?: throw IllegalStateException("provider unreachable")
        return GreenApiInstanceHealth(state = current, rawState = current.name, observedAt = Instant.now())
    }

    fun reset() {
        calls.set(0)
        state = null
    }
}
