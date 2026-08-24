package br.com.shiftcatcher.group

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/groups")
class AllowedGroupController(
    private val service: AllowedGroupService,
) {
    @GetMapping
    fun list(): AllowedGroupListResponse = service.list()

    @PostMapping
    fun register(
        @RequestBody request: RegisterGroupRequest,
    ): AllowedGroupResponse = service.register(request)

    @GetMapping("/{groupId}")
    fun detail(
        @PathVariable groupId: String,
    ): AllowedGroupResponse = service.detail(groupId)

    @PatchMapping("/{groupId}")
    fun patch(
        @PathVariable groupId: String,
        @RequestBody request: PatchGroupRequest,
    ): AllowedGroupResponse = service.patch(groupId, request)

    @PostMapping("/{groupId}/enable")
    fun enable(
        @PathVariable groupId: String,
        @RequestBody(required = false) request: VersionedRequest?,
    ): AllowedGroupResponse = service.setEnabled(groupId, enabled = true, request = request)

    @PostMapping("/{groupId}/disable")
    fun disable(
        @PathVariable groupId: String,
        @RequestBody(required = false) request: VersionedRequest?,
    ): AllowedGroupResponse = service.setEnabled(groupId, enabled = false, request = request)

    @PostMapping("/{groupId}/auto-claim/enable")
    fun enableAutoClaim(
        @PathVariable groupId: String,
        @RequestBody(required = false) request: VersionedRequest?,
    ): AllowedGroupResponse = service.setAutoClaimEnabled(groupId, autoClaimEnabled = true, request = request)

    @PostMapping("/{groupId}/auto-claim/disable")
    fun disableAutoClaim(
        @PathVariable groupId: String,
        @RequestBody(required = false) request: VersionedRequest?,
    ): AllowedGroupResponse = service.setAutoClaimEnabled(groupId, autoClaimEnabled = false, request = request)
}
