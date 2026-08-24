package br.com.shiftcatcher.rules

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/rule-sets")
class RuleSetController(
    private val service: RuleSetService,
    private val evaluationService: OpportunityEvaluationService,
) {
    @GetMapping
    fun list(): RuleSetListResponse = service.list()

    @PostMapping
    fun create(
        @RequestBody request: RuleSetRequest,
    ): RuleSetResponse = service.create(request)

    @GetMapping("/{ruleSetId}")
    fun detail(
        @PathVariable ruleSetId: String,
    ): RuleSetResponse = service.detail(ruleSetId)

    @PatchMapping("/{ruleSetId}")
    fun patch(
        @PathVariable ruleSetId: String,
        @RequestBody request: RuleSetRequest,
    ): RuleSetResponse = service.patch(ruleSetId, request)

    @PostMapping("/{ruleSetId}/activate")
    fun activate(
        @PathVariable ruleSetId: String,
    ): RuleSetResponse = service.activate(ruleSetId)

    @PostMapping("/{ruleSetId}/simulate")
    fun simulate(
        @PathVariable ruleSetId: String,
        @RequestBody(required = false) request: SimulateRequest?,
    ): SimulationResponse = evaluationService.simulate(ruleSetId, request ?: SimulateRequest())
}

/**
 * `EP-021` lives on the opportunity path but belongs to the rules module, which owns the policy that
 * decides the transition out of `EVALUATING`.
 */
@RestController
@RequestMapping("/api/v1/opportunities")
class OpportunityEvaluationController(
    private val service: OpportunityEvaluationService,
) {
    @PostMapping("/{opportunityId}/reevaluate")
    fun reevaluate(
        @PathVariable opportunityId: String,
    ): EvaluationResponse = service.reevaluate(opportunityId)
}
