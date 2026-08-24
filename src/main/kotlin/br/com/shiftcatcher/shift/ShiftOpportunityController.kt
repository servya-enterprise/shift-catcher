package br.com.shiftcatcher.shift

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/opportunities")
class ShiftOpportunityController(
    private val service: ShiftOpportunityService,
) {
    @GetMapping
    fun list(): ShiftOpportunityListResponse = service.list()

    @GetMapping("/{opportunityId}")
    fun detail(
        @PathVariable opportunityId: String,
    ): ShiftOpportunityResponse = service.detail(opportunityId)

    @PostMapping("/{opportunityId}/review")
    fun review(
        @PathVariable opportunityId: String,
        @RequestBody request: ReviewOpportunityRequest,
    ): ShiftOpportunityResponse = service.review(opportunityId, request)

    @PostMapping("/{opportunityId}/ignore")
    fun ignore(
        @PathVariable opportunityId: String,
        @RequestBody(required = false) request: IgnoreOpportunityRequest?,
    ): ShiftOpportunityResponse = service.ignore(opportunityId, request)
}
