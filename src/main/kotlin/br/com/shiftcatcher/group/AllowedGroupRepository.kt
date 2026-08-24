package br.com.shiftcatcher.group

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
class AllowedGroupRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findAll(): List<AllowedGroup> = jdbcTemplate.query("$SELECT_SQL order by created_at", ROW_MAPPER)

    fun findById(id: UUID): AllowedGroup? = jdbcTemplate.query("$SELECT_SQL where id = ?", ROW_MAPPER, id).firstOrNull()

    fun findByProviderChatId(providerChatId: String): AllowedGroup? =
        jdbcTemplate.query("$SELECT_SQL where provider_chat_id = ?", ROW_MAPPER, providerChatId).firstOrNull()

    /** Returns null when the provider chat is already registered, so the caller can answer `CONFLICT`. */
    @Transactional
    fun insert(
        providerChatId: String,
        displayName: String?,
        enabled: Boolean,
        autoClaimEnabled: Boolean,
    ): AllowedGroup? =
        jdbcTemplate
            .query(INSERT_SQL, ROW_MAPPER, providerChatId, displayName, enabled, autoClaimEnabled)
            .firstOrNull()

    /**
     * Optimistic update. Returns null when [expectedVersion] no longer matches the stored row, which the
     * caller reports as `STALE_VERSION` rather than silently overwriting a concurrent change.
     */
    @Transactional
    fun update(
        id: UUID,
        displayName: String?,
        enabled: Boolean,
        autoClaimEnabled: Boolean,
        expectedVersion: Int,
    ): AllowedGroup? =
        jdbcTemplate
            .query(UPDATE_SQL, ROW_MAPPER, displayName, enabled, autoClaimEnabled, id, expectedVersion)
            .firstOrNull()

    private companion object {
        val SELECT_SQL =
            """
            select id, provider_chat_id, display_name, enabled, auto_claim_enabled, version,
                   created_at, updated_at
              from allowed_group
            """.trimIndent()

        val INSERT_SQL =
            """
            insert into allowed_group (provider_chat_id, display_name, enabled, auto_claim_enabled)
            values (?, ?, ?, ?)
            on conflict (provider_chat_id) do nothing
            returning id, provider_chat_id, display_name, enabled, auto_claim_enabled, version,
                      created_at, updated_at
            """.trimIndent()

        val UPDATE_SQL =
            """
            update allowed_group
               set display_name = ?,
                   enabled = ?,
                   auto_claim_enabled = ?,
                   version = version + 1,
                   updated_at = current_timestamp
             where id = ?
               and version = ?
            returning id, provider_chat_id, display_name, enabled, auto_claim_enabled, version,
                      created_at, updated_at
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                AllowedGroup(
                    id = resultSet.getObject("id", UUID::class.java),
                    providerChatId = resultSet.getString("provider_chat_id"),
                    displayName = resultSet.getString("display_name"),
                    enabled = resultSet.getBoolean("enabled"),
                    autoClaimEnabled = resultSet.getBoolean("auto_claim_enabled"),
                    version = resultSet.getInt("version"),
                    createdAt = resultSet.getTimestamp("created_at").toInstant(),
                    updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
                )
            }
    }
}

data class AllowedGroup(
    val id: UUID,
    val providerChatId: String,
    val displayName: String?,
    val enabled: Boolean,
    val autoClaimEnabled: Boolean,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)
