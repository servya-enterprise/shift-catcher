package br.com.shiftcatcher.claim

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/settings/claim-message")
class ClaimMessageController(
    private val service: ClaimMessageService,
) {
    /** `EP-038`: the wording every group falls back to. */
    @GetMapping
    fun current(): ClaimMessageResponse = service.current()

    /** `EP-039`: changes it. A group can still override it through `EP-010`. */
    @PutMapping
    fun update(
        @RequestBody request: ClaimMessageRequest,
    ): ClaimMessageResponse = service.update(request)
}
