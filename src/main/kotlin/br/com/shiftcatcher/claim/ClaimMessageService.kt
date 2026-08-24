package br.com.shiftcatcher.claim

import br.com.shiftcatcher.foundation.http.ApiProblemException
import br.com.shiftcatcher.group.AllowedGroup
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The wording of the reply.
 *
 * `00-Start/POC-Freeze.md` fixed it as `PEGO`, which was right for proving the transport and wrong
 * for a product: the phrase a doctor uses to take a shift belongs to her, not to us. The default
 * stays `PEGO`, so an installation that never touches this behaves exactly as the POC did.
 *
 * Resolution is per group with a global fallback, because groups are different hospitals with
 * different customs. The resolved text is frozen onto the claim at decision time, next to the quote
 * target and for the same reason: what was sent must stay knowable even after the setting changes.
 */
@Service
class ClaimMessageService(
    private val repository: ClaimMessageRepository,
) {
    /** `EP-038`. */
    fun current(): ClaimMessageResponse = repository.load().toResponse()

    /** `EP-039`. */
    fun update(request: ClaimMessageRequest): ClaimMessageResponse {
        val message =
            request.message?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("message is required and must not be blank")
        require(message.length <= MAX_MESSAGE) { "message exceeds $MAX_MESSAGE characters" }
        // A reply that quotes the offer is a WhatsApp text like any other; the only shape we insist
        // on is that it is one line, because a multi-line claim reads as a conversation, not a bid.
        require(!message.contains('\n')) { "message must be a single line" }

        val current = repository.load()
        if (message == current.message) {
            return current.toResponse()
        }
        if (request.version != null && request.version != current.version) {
            throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "STALE_VERSION",
                title = "Stale version",
                message = "The claim message was changed by another request; reload it and retry",
            )
        }
        return repository.update(message, current.version)?.toResponse()
            ?: throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "STALE_VERSION",
                title = "Stale version",
                message = "The claim message changed while it was being written; reload it and retry",
            )
    }

    /** What this group's claim should say right now. The group's own wording wins when it has one. */
    fun resolveFor(group: AllowedGroup): String = group.claimMessage ?: repository.load().message

    private fun ClaimMessageSetting.toResponse(): ClaimMessageResponse =
        ClaimMessageResponse(message = message, version = version, updatedAt = updatedAt)

    private companion object {
        const val MAX_MESSAGE = 512
    }
}

@Repository
class ClaimMessageRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    /** The row is created by `V10`; its absence would mean a broken migration, not an empty state. */
    fun load(): ClaimMessageSetting =
        jdbcTemplate.queryForObject("select message, version, updated_at from claim_message_setting", ROW_MAPPER)!!

    @Transactional
    fun update(
        message: String,
        expectedVersion: Int,
    ): ClaimMessageSetting? =
        jdbcTemplate
            .query(UPDATE_SQL, ROW_MAPPER, message, expectedVersion)
            .firstOrNull()

    private companion object {
        val UPDATE_SQL =
            """
            update claim_message_setting
               set message = ?,
                   version = version + 1,
                   updated_at = current_timestamp
             where version = ?
            returning message, version, updated_at
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                ClaimMessageSetting(
                    message = resultSet.getString("message"),
                    version = resultSet.getInt("version"),
                    updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
                )
            }
    }
}

data class ClaimMessageSetting(
    val message: String,
    val version: Int,
    val updatedAt: Instant,
)

data class ClaimMessageRequest(
    val message: String? = null,
    val version: Int? = null,
)

data class ClaimMessageResponse(
    val message: String,
    val version: Int,
    val updatedAt: Instant,
)
