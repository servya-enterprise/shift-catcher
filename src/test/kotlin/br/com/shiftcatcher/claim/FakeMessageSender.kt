package br.com.shiftcatcher.claim

import br.com.shiftcatcher.integration.greenapi.ProviderSendReceipt
import br.com.shiftcatcher.integration.greenapi.SendQuotedMessage
import br.com.shiftcatcher.integration.greenapi.WhatsAppMessageSender
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records every send the worker performs. The count is the assertion that matters: the acceptance
 * criterion is one logical WhatsApp message per opportunity, however many times anything is retried.
 */
class FakeMessageSender : WhatsAppMessageSender {
    val sends = CopyOnWriteArrayList<SendQuotedMessage>()
    val calls = AtomicInteger()

    /** Number of leading calls that should fail before one succeeds. */
    @Volatile
    var failuresBeforeSuccess: Int = 0

    @Volatile
    var failure: RuntimeException? = null

    override fun sendQuotedMessage(command: SendQuotedMessage): ProviderSendReceipt {
        val attempt = calls.incrementAndGet()
        failure?.let { scripted ->
            if (failuresBeforeSuccess == 0 || attempt <= failuresBeforeSuccess) {
                throw scripted
            }
        }
        sends += command
        return ProviderSendReceipt(providerMessageId = "provider-out-$attempt", acceptedAt = Instant.now())
    }

    fun reset() {
        sends.clear()
        calls.set(0)
        failuresBeforeSuccess = 0
        failure = null
    }
}
