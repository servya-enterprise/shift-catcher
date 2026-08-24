package br.com.shiftcatcher.messaging

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class IncomingMessageRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * One normalized message per provider event. The upsert keeps ingestion replay-safe: a webhook
     * delivered twice converges on the same row instead of duplicating the message log.
     */
    @Transactional
    fun upsert(message: NormalizedMessage): UUID =
        jdbcTemplate.queryForObject(
            UPSERT_SQL,
            UUID::class.java,
            message.providerEventId,
            message.groupId,
            message.providerMessageId,
            message.chatId,
            message.chatName,
            message.senderId,
            message.senderName,
            message.text,
            Timestamp.from(message.providerTimestamp),
            Timestamp.from(message.receivedAt),
        )!!

    @Transactional
    fun updateGroup(
        messageId: UUID,
        groupId: UUID?,
    ) {
        jdbcTemplate.update(UPDATE_GROUP_SQL, groupId, messageId)
    }

    fun findById(id: UUID): IncomingMessageRecord? = jdbcTemplate.query("$SELECT_SQL where m.id = ?", ROW_MAPPER, id).firstOrNull()

    fun findByProviderEventId(providerEventId: UUID): IncomingMessageRecord? =
        jdbcTemplate.query("$SELECT_SQL where m.provider_event_id = ?", ROW_MAPPER, providerEventId).firstOrNull()

    fun findRecent(limit: Int): List<IncomingMessageRecord> =
        jdbcTemplate.query("$SELECT_SQL order by m.received_at desc, m.id desc limit ?", ROW_MAPPER, limit)

    private companion object {
        val SELECT_SQL =
            """
            select m.id, m.provider_event_id, m.group_id, m.provider_message_id, m.chat_id,
                   m.chat_name, m.sender_id, m.sender_name, m.text, m.provider_timestamp,
                   m.received_at, e.persisted_at, e.processing_status, e.ignored_reason
              from incoming_message m
              join incoming_provider_event e on e.id = m.provider_event_id
            """.trimIndent()

        val UPSERT_SQL =
            """
            insert into incoming_message (
                provider_event_id, group_id, provider_message_id, chat_id, chat_name,
                sender_id, sender_name, text, provider_timestamp, received_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (provider_event_id) do update
               set group_id = excluded.group_id,
                   text = excluded.text,
                   updated_at = current_timestamp
            returning id
            """.trimIndent()

        val UPDATE_GROUP_SQL =
            """
            update incoming_message
               set group_id = ?,
                   updated_at = current_timestamp
             where id = ?
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { resultSet, _ ->
                IncomingMessageRecord(
                    id = resultSet.getObject("id", UUID::class.java),
                    providerEventId = resultSet.getObject("provider_event_id", UUID::class.java),
                    groupId = resultSet.getObject("group_id", UUID::class.java),
                    providerMessageId = resultSet.getString("provider_message_id"),
                    chatId = resultSet.getString("chat_id"),
                    chatName = resultSet.getString("chat_name"),
                    senderId = resultSet.getString("sender_id"),
                    senderName = resultSet.getString("sender_name"),
                    text = resultSet.getString("text"),
                    providerTimestamp = resultSet.getTimestamp("provider_timestamp").toInstant(),
                    receivedAt = resultSet.getTimestamp("received_at").toInstant(),
                    persistedAt = resultSet.getTimestamp("persisted_at").toInstant(),
                    processingStatus = ProcessingStatus.valueOf(resultSet.getString("processing_status")),
                    ignoredReason = resultSet.getString("ignored_reason")?.let(IgnoredReason::valueOf),
                )
            }
    }
}

data class NormalizedMessage(
    val providerEventId: UUID,
    val groupId: UUID?,
    val providerMessageId: String,
    val chatId: String,
    val chatName: String?,
    val senderId: String,
    val senderName: String?,
    val text: String,
    val providerTimestamp: Instant,
    val receivedAt: Instant,
)

data class IncomingMessageRecord(
    val id: UUID,
    val providerEventId: UUID,
    val groupId: UUID?,
    val providerMessageId: String,
    val chatId: String,
    val chatName: String?,
    val senderId: String,
    val senderName: String?,
    val text: String,
    val providerTimestamp: Instant,
    val receivedAt: Instant,
    val persistedAt: Instant,
    val processingStatus: ProcessingStatus,
    val ignoredReason: IgnoredReason?,
)
