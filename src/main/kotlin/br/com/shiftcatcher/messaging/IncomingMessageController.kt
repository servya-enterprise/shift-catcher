package br.com.shiftcatcher.messaging

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/messages")
class IncomingMessageController(
    private val service: IngestionService,
) {
    @GetMapping
    fun list(): IncomingMessageListResponse = service.list()

    @GetMapping("/{messageId}")
    fun detail(
        @PathVariable messageId: String,
    ): IncomingMessageResponse = service.detail(messageId)

    @PostMapping("/{messageId}/reprocess")
    fun reprocess(
        @PathVariable messageId: String,
    ): ReprocessResponse = service.reprocess(messageId)
}
