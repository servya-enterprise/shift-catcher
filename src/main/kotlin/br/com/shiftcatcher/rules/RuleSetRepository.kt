package br.com.shiftcatcher.rules

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class RuleSetRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findAll(): List<RuleSetRecord> = jdbcTemplate.query("$SELECT_SQL order by version desc", ROW_MAPPER)

    fun findById(id: UUID): RuleSetRecord? = jdbcTemplate.query("$SELECT_SQL where id = ?", ROW_MAPPER, id).firstOrNull()

    fun findActive(): RuleSetRecord? = jdbcTemplate.query("$SELECT_SQL where status = 'ACTIVE'", ROW_MAPPER).firstOrNull()

    @Transactional
    fun insertDraft(
        name: String?,
        definitionJson: String,
    ): RuleSetRecord = jdbcTemplate.queryForObject(INSERT_SQL, ROW_MAPPER, name, definitionJson)!!

    /** Only a draft can change; an active or superseded version is immutable by contract. */
    @Transactional
    fun updateDraft(
        id: UUID,
        name: String?,
        definitionJson: String,
    ): RuleSetRecord? = jdbcTemplate.query(UPDATE_DRAFT_SQL, ROW_MAPPER, name, definitionJson, id).firstOrNull()

    /**
     * Supersedes the current active version and promotes this one in a single transaction. The
     * partial unique index on `status = 'ACTIVE'` is what actually guarantees there is never a
     * moment with two active rule sets.
     */
    @Transactional
    fun activate(id: UUID): RuleSetRecord? {
        jdbcTemplate.update(SUPERSEDE_SQL, Timestamp.from(Instant.now()), id)
        return jdbcTemplate.query(ACTIVATE_SQL, ROW_MAPPER, Timestamp.from(Instant.now()), id).firstOrNull()
    }

    private companion object {
        val SELECT_SQL =
            """
            select id, version, name, status, definition::text as definition, created_at, updated_at,
                   activated_at, superseded_at
              from rule_set
            """.trimIndent()

        val INSERT_SQL =
            """
            insert into rule_set (version, name, status, definition)
            values (
                (select coalesce(max(version), 0) + 1 from rule_set),
                ?,
                'DRAFT',
                ?::jsonb
            )
            returning id, version, name, status, definition::text as definition, created_at,
                      updated_at, activated_at, superseded_at
            """.trimIndent()

        val UPDATE_DRAFT_SQL =
            """
            update rule_set
               set name = ?,
                   definition = ?::jsonb,
                   updated_at = current_timestamp
             where id = ?
               and status = 'DRAFT'
            returning id, version, name, status, definition::text as definition, created_at,
                      updated_at, activated_at, superseded_at
            """.trimIndent()

        val SUPERSEDE_SQL =
            """
            update rule_set
               set status = 'SUPERSEDED',
                   superseded_at = ?,
                   updated_at = current_timestamp
             where status = 'ACTIVE'
               and id <> ?
            """.trimIndent()

        val ACTIVATE_SQL =
            """
            update rule_set
               set status = 'ACTIVE',
                   activated_at = coalesce(activated_at, ?),
                   updated_at = current_timestamp
             where id = ?
               and status = 'DRAFT'
            returning id, version, name, status, definition::text as definition, created_at,
                      updated_at, activated_at, superseded_at
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                RuleSetRecord(
                    id = resultSet.getObject("id", UUID::class.java),
                    version = resultSet.getInt("version"),
                    name = resultSet.getString("name"),
                    status = RuleSetStatus.valueOf(resultSet.getString("status")),
                    definitionJson = resultSet.getString("definition"),
                    createdAt = resultSet.getTimestamp("created_at").toInstant(),
                    updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
                    activatedAt = resultSet.getTimestamp("activated_at")?.toInstant(),
                    supersededAt = resultSet.getTimestamp("superseded_at")?.toInstant(),
                )
            }
    }
}

data class RuleSetRecord(
    val id: UUID,
    val version: Int,
    val name: String?,
    val status: RuleSetStatus,
    val definitionJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val activatedAt: Instant?,
    val supersededAt: Instant?,
)
