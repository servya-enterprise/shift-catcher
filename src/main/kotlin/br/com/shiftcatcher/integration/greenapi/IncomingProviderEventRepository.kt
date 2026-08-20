package br.com.shiftcatcher.integration.greenapi

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class IncomingProviderEventRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun record(message: IncomingTransportMessage): RecordedProviderEvent {
        val inserted =
            jdbcTemplate
                .query(
                    INSERT_SQL,
                    { resultSet, _ ->
                        RecordedProviderEvent(
                            id = resultSet.getObject("id", UUID::class.java),
                            persistedAt = resultSet.getTimestamp("persisted_at").toInstant(),
                            duplicate = false,
                        )
                    },
                    message.instanceId,
                    message.webhookType,
                    message.providerMessageId,
                    Timestamp.from(message.providerTimestamp),
                    Timestamp.from(message.webhookReceivedAt),
                    Timestamp.from(message.parsingCompletedAt),
                    message.chatId,
                    message.chatName,
                    message.senderId,
                    message.senderName,
                    message.senderContactName,
                    message.messageType,
                    message.messageText,
                ).firstOrNull()
        if (inserted != null) {
            return inserted
        }

        return jdbcTemplate.queryForObject(
            DUPLICATE_SQL,
            { resultSet, _ ->
                RecordedProviderEvent(
                    id = resultSet.getObject("id", UUID::class.java),
                    persistedAt = resultSet.getTimestamp("persisted_at").toInstant(),
                    duplicate = true,
                )
            },
            message.instanceId,
            message.webhookType,
            message.providerMessageId,
        )
    }

    fun latestGroupMessage(): LatestProviderEvent? =
        jdbcTemplate
            .query(
                LATEST_SQL,
                { resultSet, _ ->
                    LatestProviderEvent(
                        id = resultSet.getObject("id", UUID::class.java),
                        chatId = resultSet.getString("chat_id"),
                        senderId = resultSet.getString("sender_id"),
                        providerMessageId = resultSet.getString("provider_message_id"),
                        providerTimestamp = resultSet.getTimestamp("provider_timestamp").toInstant(),
                        webhookReceivedAt = resultSet.getTimestamp("webhook_received_at").toInstant(),
                        persistedAt = resultSet.getTimestamp("persisted_at").toInstant(),
                        messageText = resultSet.getString("message_text"),
                    )
                },
            ).firstOrNull()

    private companion object {
        val INSERT_SQL =
            """
            insert into incoming_provider_event (
                provider, instance_id, webhook_type, provider_message_id, provider_timestamp,
                webhook_received_at, parsing_completed_at, chat_id, chat_name, sender_id,
                sender_name, sender_contact_name, message_type, message_text
            ) values (
                'GREEN_API', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            on conflict (provider, instance_id, webhook_type, provider_message_id) do nothing
            returning id, persisted_at
            """.trimIndent()

        val DUPLICATE_SQL =
            """
            update incoming_provider_event
               set duplicate_count = duplicate_count + 1
             where provider = 'GREEN_API'
               and instance_id = ?
               and webhook_type = ?
               and provider_message_id = ?
            returning id, persisted_at
            """.trimIndent()

        val LATEST_SQL =
            """
            select id, chat_id, sender_id, provider_message_id, provider_timestamp,
                   webhook_received_at, persisted_at, message_text
              from incoming_provider_event
             where provider = 'GREEN_API' and chat_id like '%@g.us'
             order by persisted_at desc
             limit 1
            """.trimIndent()
    }
}

data class IncomingTransportMessage(
    val instanceId: String,
    val webhookType: String,
    val providerMessageId: String,
    val providerTimestamp: Instant,
    val webhookReceivedAt: Instant,
    val parsingCompletedAt: Instant,
    val chatId: String,
    val chatName: String?,
    val senderId: String,
    val senderName: String?,
    val senderContactName: String?,
    val messageType: String,
    val messageText: String,
)

data class RecordedProviderEvent(
    val id: UUID,
    val persistedAt: Instant,
    val duplicate: Boolean,
)

data class LatestProviderEvent(
    val id: UUID,
    val chatId: String,
    val senderId: String,
    val providerMessageId: String,
    val providerTimestamp: Instant,
    val webhookReceivedAt: Instant,
    val persistedAt: Instant,
    val messageText: String,
)
