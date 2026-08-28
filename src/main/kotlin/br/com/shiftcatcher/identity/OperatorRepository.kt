package br.com.shiftcatcher.identity

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class Operator(
    val id: UUID,
    val idpSubject: String,
    val displayName: String,
    val status: String,
    val createdAt: Instant,
    val lastSeenAt: Instant?,
) {
    val active: Boolean get() = status == "ACTIVE"
}

/**
 * Who this product will let in, and which introductions it has already spent.
 *
 * AUTODEC-0012 decisions 4 and 5. Clara Care says who you are; this table says whether that is
 * anybody here. A valid assertion for a subject with no row is 403 and not a new operator — the
 * fact that Clara Care knows somebody says nothing about whether this product should.
 */
@Repository
class OperatorRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findBySubject(subject: String): Operator? =
        jdbcTemplate
            .query("$SELECT_SQL where idp_subject = ?", ROW_MAPPER, subject)
            .firstOrNull()

    @Transactional
    fun markSeen(
        id: UUID,
        at: Instant,
    ) {
        jdbcTemplate.update("update operator set last_seen_at = ? where id = ?", java.sql.Timestamp.from(at), id)
    }

    /**
     * Spends an introduction, or reports that it was already spent.
     *
     * The uniqueness of the primary key is what enforces single use, not a read followed by a
     * write: two tabs opening the same link at the same moment both pass a `select` and only one
     * can pass the `insert`. Returning false rather than throwing lets the caller answer the second
     * one with the same refusal a replay from anywhere else would get.
     */
    @Transactional
    fun redeem(
        jti: String,
        expiresAt: Instant,
    ): Boolean =
        try {
            jdbcTemplate.update(
                "insert into handoff_redemption (jti, expires_at) values (?, ?)",
                jti,
                java.sql.Timestamp.from(expiresAt),
            )
            true
        } catch (_: DuplicateKeyException) {
            false
        }

    /**
     * Drops what can no longer be replayed.
     *
     * A redemption is only interesting while the assertion it belongs to could still be presented
     * again, and that window is sixty seconds. Keeping the rows past their expiry would build an
     * unbounded table recording nothing anybody will ever read.
     */
    @Transactional
    fun forgetExpired(now: Instant): Int = jdbcTemplate.update("delete from handoff_redemption where expires_at < ?", java.sql.Timestamp.from(now))

    private companion object {
        const val SELECT_SQL =
            "select id, idp_subject, display_name, status, created_at, last_seen_at from operator"

        val ROW_MAPPER =
            RowMapper { rs, _ ->
                Operator(
                    id = rs.getObject("id", UUID::class.java),
                    idpSubject = rs.getString("idp_subject"),
                    displayName = rs.getString("display_name"),
                    status = rs.getString("status"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    lastSeenAt = rs.getTimestamp("last_seen_at")?.toInstant(),
                )
            }
    }
}
