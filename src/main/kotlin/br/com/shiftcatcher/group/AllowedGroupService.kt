package br.com.shiftcatcher.group

import br.com.shiftcatcher.foundation.http.ApiProblemException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

const val GROUP_CHAT_SUFFIX = "@g.us"

@Service
class AllowedGroupService(
    private val repository: AllowedGroupRepository,
) {
    fun list(): AllowedGroupListResponse {
        val groups = repository.findAll().map { it.toResponse() }
        return AllowedGroupListResponse(groups = groups, count = groups.size)
    }

    fun register(request: RegisterGroupRequest): AllowedGroupResponse {
        val providerChatId = requiredBounded(request.providerChatId, "providerChatId", 128)
        require(providerChatId.endsWith(GROUP_CHAT_SUFFIX)) {
            "providerChatId must identify a group and end with $GROUP_CHAT_SUFFIX"
        }
        val displayName = request.displayName?.takeIf { it.isNotBlank() }?.also { bounded(it, "displayName", 256) }
        val created =
            repository.insert(
                providerChatId = providerChatId,
                displayName = displayName,
                enabled = request.enabled ?: true,
                // Auto-claim is never enabled implicitly: DEC-005 keeps the unsafe direction opt-in only.
                autoClaimEnabled = request.autoClaimEnabled ?: false,
                claimMessage = claimMessageOrNull(request.claimMessage),
            )
                ?: throw ApiProblemException(
                    status = HttpStatus.CONFLICT,
                    code = "CONFLICT",
                    title = "Group already registered",
                    message = "providerChatId is already present in the allowlist",
                )
        return created.toResponse()
    }

    fun detail(groupId: String): AllowedGroupResponse = load(groupId).toResponse()

    fun patch(
        groupId: String,
        request: PatchGroupRequest,
    ): AllowedGroupResponse {
        val current = load(groupId)
        val version =
            request.version
                ?: throw ApiProblemException(
                    status = HttpStatus.BAD_REQUEST,
                    code = "INVALID_REQUEST",
                    title = "Invalid request",
                    message = "version is required so a concurrent change is never overwritten silently",
                )
        val displayName =
            when {
                request.displayName == null -> current.displayName
                request.displayName.isBlank() -> null
                else -> bounded(request.displayName, "displayName", 256)
            }
        // Same convention as displayName: omitted keeps it, blank clears it back to the global
        // wording. There is no third state to express, so none is invented.
        val claimMessage =
            when {
                request.claimMessage == null -> current.claimMessage
                request.claimMessage.isBlank() -> null
                else -> claimMessageOrNull(request.claimMessage)
            }
        return applyUpdate(
            current = current,
            displayName = displayName,
            enabled = request.enabled ?: current.enabled,
            autoClaimEnabled = request.autoClaimEnabled ?: current.autoClaimEnabled,
            claimMessage = claimMessage,
            expectedVersion = version,
        )
    }

    fun setEnabled(
        groupId: String,
        enabled: Boolean,
        request: VersionedRequest?,
    ): AllowedGroupResponse {
        val current = load(groupId)
        ensureFreshVersion(current, request?.version)
        return applyUpdate(
            current = current,
            displayName = current.displayName,
            enabled = enabled,
            autoClaimEnabled = current.autoClaimEnabled,
            claimMessage = current.claimMessage,
            expectedVersion = current.version,
        )
    }

    fun setAutoClaimEnabled(
        groupId: String,
        autoClaimEnabled: Boolean,
        request: VersionedRequest?,
    ): AllowedGroupResponse {
        val current = load(groupId)
        ensureFreshVersion(current, request?.version)
        return applyUpdate(
            current = current,
            displayName = current.displayName,
            enabled = current.enabled,
            autoClaimEnabled = autoClaimEnabled,
            claimMessage = current.claimMessage,
            expectedVersion = current.version,
        )
    }

    private fun ensureFreshVersion(
        current: AllowedGroup,
        suppliedVersion: Int?,
    ) {
        if (suppliedVersion != null && suppliedVersion != current.version) {
            throw staleVersion()
        }
    }

    private fun applyUpdate(
        current: AllowedGroup,
        displayName: String?,
        enabled: Boolean,
        autoClaimEnabled: Boolean,
        claimMessage: String?,
        expectedVersion: Int,
    ): AllowedGroupResponse {
        // A request that would not change anything does not consume a version, so repeating a
        // toggle stays idempotent instead of inflating the version other callers are holding.
        val unchanged =
            displayName == current.displayName &&
                enabled == current.enabled &&
                autoClaimEnabled == current.autoClaimEnabled &&
                claimMessage == current.claimMessage
        if (unchanged && expectedVersion == current.version) {
            return current.toResponse()
        }
        return repository
            .update(
                id = current.id,
                displayName = displayName,
                enabled = enabled,
                autoClaimEnabled = autoClaimEnabled,
                claimMessage = claimMessage,
                expectedVersion = expectedVersion,
            )?.toResponse()
            ?: throw staleVersion()
    }

    private fun staleVersion(): ApiProblemException =
        ApiProblemException(
            status = HttpStatus.CONFLICT,
            code = "STALE_VERSION",
            title = "Stale version",
            message = "The group was modified by another request; reload it and retry",
        )

    private fun load(groupId: String): AllowedGroup =
        repository.findById(parseId(groupId))
            ?: throw ApiProblemException(
                status = HttpStatus.NOT_FOUND,
                code = "RESOURCE_NOT_FOUND",
                title = "Group not found",
                message = "No allowed group matches the supplied identifier",
            )

    private fun parseId(groupId: String): UUID =
        runCatching { UUID.fromString(groupId) }
            .getOrElse { throw IllegalArgumentException("groupId must be a UUID") }

    private fun requiredBounded(
        value: String?,
        field: String,
        maximum: Int,
    ): String {
        val result = value?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$field is required")
        return bounded(result, field, maximum)
    }

    /** Blank is not an override, it is the absence of one, and the database refuses it either way. */
    private fun claimMessageOrNull(value: String?): String? {
        val message = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        bounded(message, "claimMessage", MAX_CLAIM_MESSAGE)
        require(!message.contains('\n')) { "claimMessage must be a single line" }
        return message
    }

    private fun bounded(
        value: String,
        field: String,
        maximum: Int,
    ): String {
        require(value.length <= maximum) { "$field exceeds $maximum characters" }
        return value
    }

    private fun AllowedGroup.toResponse(): AllowedGroupResponse =
        AllowedGroupResponse(
            id = id.toString(),
            providerChatId = providerChatId,
            displayName = displayName,
            enabled = enabled,
            autoClaimEnabled = autoClaimEnabled,
            claimMessage = claimMessage,
            version = version,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private companion object {
        const val MAX_CLAIM_MESSAGE = 512
    }
}

data class RegisterGroupRequest(
    val providerChatId: String? = null,
    val displayName: String? = null,
    val enabled: Boolean? = null,
    val autoClaimEnabled: Boolean? = null,
    val claimMessage: String? = null,
)

data class PatchGroupRequest(
    val displayName: String? = null,
    val enabled: Boolean? = null,
    val autoClaimEnabled: Boolean? = null,
    /** Omit to keep the current override, send blank to fall back to the global wording. */
    val claimMessage: String? = null,
    val version: Int? = null,
)

data class VersionedRequest(
    val version: Int? = null,
)

data class AllowedGroupResponse(
    val id: String,
    val providerChatId: String,
    val displayName: String?,
    val enabled: Boolean,
    val autoClaimEnabled: Boolean,
    val claimMessage: String?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AllowedGroupListResponse(
    val groups: List<AllowedGroupResponse>,
    val count: Int,
)
