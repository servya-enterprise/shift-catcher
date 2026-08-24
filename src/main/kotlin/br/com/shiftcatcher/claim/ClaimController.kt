package br.com.shiftcatcher.claim

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/claims")
class ClaimController(
    private val service: ClaimService,
) {
    @GetMapping
    fun list(): ClaimListResponse = service.list()

    @GetMapping("/{claimId}")
    fun detail(
        @PathVariable claimId: String,
    ): ClaimResponse = service.detail(claimId)

    @PostMapping("/{claimId}/retry")
    fun retry(
        @PathVariable claimId: String,
    ): ClaimResponse = service.retry(claimId)

    /** `EP-037`: takes back a PEGO that should not have been sent. */
    @PostMapping("/{claimId}/retract")
    fun retract(
        @PathVariable claimId: String,
        @RequestBody(required = false) request: RetractClaimRequest?,
    ): ClaimResponse = service.retract(claimId, request)
}

/** `EP-023` sits on the opportunity path but is owned by the claim module. */
@RestController
@RequestMapping("/api/v1/opportunities")
class OpportunityClaimController(
    private val service: ClaimService,
) {
    @PostMapping("/{opportunityId}/claim")
    fun claim(
        @PathVariable opportunityId: String,
        @RequestBody(required = false) request: ClaimRequest?,
    ): ClaimResponse = service.claim(opportunityId, request)
}
