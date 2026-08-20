package br.com.shiftcatcher.integration.greenapi

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class GreenApiTransportController(
    private val service: GreenApiTransportService,
) {
    @GetMapping("/integrations/green-api/state")
    fun state(): GreenApiStateResponse = service.state()

    @PostMapping("/integrations/green-api/verify")
    fun verify(): GreenApiVerificationResponse = service.verify()

    @PostMapping("/poc/send-test-reply")
    fun sendTestReply(
        @RequestBody request: SendTestReplyRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): SendTestReplyResponse = service.sendTestReply(request, idempotencyKey)
}
