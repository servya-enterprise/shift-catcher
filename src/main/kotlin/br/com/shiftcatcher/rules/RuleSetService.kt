package br.com.shiftcatcher.rules

import br.com.shiftcatcher.foundation.http.ApiProblemException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Service
class RuleSetService(
    private val repository: RuleSetRepository,
    private val objectMapper: ObjectMapper,
) {
    fun list(): RuleSetListResponse {
        val ruleSets = repository.findAll().map { it.toResponse() }
        return RuleSetListResponse(ruleSets = ruleSets, count = ruleSets.size)
    }

    fun create(request: RuleSetRequest): RuleSetResponse {
        val definition = parse(request.definition)
        return repository.insertDraft(request.name?.take(128), objectMapper.writeValueAsString(definition)).toResponse()
    }

    fun detail(ruleSetId: String): RuleSetResponse = load(ruleSetId).toResponse()

    fun patch(
        ruleSetId: String,
        request: RuleSetRequest,
    ): RuleSetResponse {
        val current = load(ruleSetId)
        if (current.status != RuleSetStatus.DRAFT) {
            throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Rule set is immutable",
                message = "Only a DRAFT rule set can be edited; version ${current.version} is ${current.status}",
            )
        }
        val definition = parse(request.definition)
        return repository
            .updateDraft(current.id, request.name?.take(128), objectMapper.writeValueAsString(definition))
            ?.toResponse()
            ?: throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Rule set is immutable",
                message = "The rule set stopped being a draft while it was being edited",
            )
    }

    fun activate(ruleSetId: String): RuleSetResponse {
        val current = load(ruleSetId)
        // Activating what is already active is a no-op rather than an error.
        if (current.status == RuleSetStatus.ACTIVE) {
            return current.toResponse()
        }
        if (current.status == RuleSetStatus.SUPERSEDED) {
            throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Rule set is superseded",
                message = "A superseded rule set cannot be reactivated; create a new draft instead",
            )
        }
        return repository.activate(current.id)?.toResponse()
            ?: throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Rule set could not be activated",
                message = "The rule set changed status while it was being activated",
            )
    }

    fun activeDefinition(): ActiveRuleSet? {
        val active = repository.findActive() ?: return null
        return ActiveRuleSet(
            id = active.id,
            version = active.version,
            definition = objectMapper.readValue(active.definitionJson, RuleDefinition::class.java),
        )
    }

    fun definitionOf(ruleSetId: String): ActiveRuleSet {
        val record = load(ruleSetId)
        return ActiveRuleSet(
            id = record.id,
            version = record.version,
            definition = objectMapper.readValue(record.definitionJson, RuleDefinition::class.java),
        )
    }

    private fun parse(raw: Map<String, Any?>?): RuleDefinition {
        val definition =
            runCatching { objectMapper.convertValue(raw ?: emptyMap<String, Any?>(), RuleDefinition::class.java) }
                .getOrElse { throw IllegalArgumentException("definition is not a valid rule set: ${it.message}") }
        definition.validate()
        return definition
    }

    private fun load(ruleSetId: String): RuleSetRecord =
        repository.findById(parseId(ruleSetId))
            ?: throw ApiProblemException(
                status = HttpStatus.NOT_FOUND,
                code = "RESOURCE_NOT_FOUND",
                title = "Rule set not found",
                message = "No rule set matches the supplied identifier",
            )

    private fun parseId(ruleSetId: String): UUID =
        runCatching { UUID.fromString(ruleSetId) }
            .getOrElse { throw IllegalArgumentException("ruleSetId must be a UUID") }

    private fun RuleSetRecord.toResponse(): RuleSetResponse =
        RuleSetResponse(
            id = id.toString(),
            version = version,
            name = name,
            status = status,
            definition = objectMapper.readValue(definitionJson, RuleDefinition::class.java),
            createdAt = createdAt,
            updatedAt = updatedAt,
            activatedAt = activatedAt,
            supersededAt = supersededAt,
        )
}

data class ActiveRuleSet(
    val id: UUID,
    val version: Int,
    val definition: RuleDefinition,
)

data class RuleSetRequest(
    val name: String? = null,
    val definition: Map<String, Any?>? = null,
)

data class RuleSetResponse(
    val id: String,
    val version: Int,
    val name: String?,
    val status: RuleSetStatus,
    val definition: RuleDefinition,
    val createdAt: Instant,
    val updatedAt: Instant,
    val activatedAt: Instant?,
    val supersededAt: Instant?,
)

data class RuleSetListResponse(
    val ruleSets: List<RuleSetResponse>,
    val count: Int,
)
