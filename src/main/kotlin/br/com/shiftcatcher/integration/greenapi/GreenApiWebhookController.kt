package br.com.shiftcatcher.integration.greenapi

import br.com.shiftcatcher.foundation.http.CORRELATION_ID_MDC_KEY
import br.com.shiftcatcher.foundation.http.REQUEST_RECEIVED_AT_ATTRIBUTE
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/webhooks/green-api")
class GreenApiWebhookController(
    private val service: GreenApiWebhookService,
) {
    @PostMapping
    fun receive(
        @RequestBody envelope: GreenApiWebhookEnvelope,
        request: HttpServletRequest,
    ): WebhookIngestionResponse {
        val receivedAt = request.getAttribute(REQUEST_RECEIVED_AT_ATTRIBUTE) as? Instant ?: Instant.now()
        return service.ingest(
            envelope = envelope,
            receivedAt = receivedAt,
            correlationId = request.getAttribute(CORRELATION_ID_MDC_KEY) as? String,
            payloadHash = request.getAttribute(WEBHOOK_PAYLOAD_HASH_ATTRIBUTE) as? String,
        )
    }
}
