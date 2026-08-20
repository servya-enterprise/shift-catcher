package br.com.shiftcatcher.integration.greenapi

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
class GreenApiWebhookService(
    private val repository: IncomingProviderEventRepository,
    private val properties: ShiftCatcherProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun ingest(
        envelope: GreenApiWebhookEnvelope,
        receivedAt: Instant,
    ): WebhookIngestionResponse {
        if (envelope.typeWebhook != INCOMING_MESSAGE_WEBHOOK) {
            return WebhookIngestionResponse(
                status = WebhookIngestionStatus.IGNORED,
                receivedAt = receivedAt,
            )
        }

        val instanceId = required(envelope.instanceData?.idInstance?.toString(), "instanceData.idInstance")
        val configuredInstance = properties.greenApi.instanceId
        require(configuredInstance.isBlank() || configuredInstance == instanceId) {
            "Webhook instanceData.idInstance does not match the configured instance"
        }
        val timestamp = envelope.timestamp ?: throw IllegalArgumentException("timestamp is required")
        require(timestamp > 0) { "timestamp must be positive" }
        val providerTimestamp =
            runCatching { Instant.ofEpochSecond(timestamp) }
                .getOrElse { throw IllegalArgumentException("timestamp is invalid") }
        val providerMessageId = required(envelope.idMessage, "idMessage")
        val senderData = envelope.senderData ?: throw IllegalArgumentException("senderData is required")
        val chatId = required(senderData.chatId, "senderData.chatId")
        require(chatId.endsWith(GROUP_CHAT_SUFFIX)) { "senderData.chatId must identify a group" }
        val senderId = required(senderData.sender, "senderData.sender")
        val messageData = envelope.messageData ?: throw IllegalArgumentException("messageData is required")
        require(messageData.typeMessage == TEXT_MESSAGE_TYPE) { "messageData.typeMessage must be textMessage" }
        val text = required(messageData.textMessageData?.textMessage, "messageData.textMessageData.textMessage")

        val recorded =
            repository.record(
                IncomingTransportMessage(
                    instanceId = instanceId,
                    webhookType = INCOMING_MESSAGE_WEBHOOK,
                    providerMessageId = bounded(providerMessageId, "idMessage", 128),
                    providerTimestamp = providerTimestamp,
                    webhookReceivedAt = receivedAt,
                    parsingCompletedAt = clock.instant(),
                    chatId = bounded(chatId, "senderData.chatId", 128),
                    chatName = senderData.chatName?.take(256),
                    senderId = bounded(senderId, "senderData.sender", 128),
                    senderName = senderData.senderName?.take(256),
                    senderContactName = senderData.senderContactName?.take(256),
                    messageType = TEXT_MESSAGE_TYPE,
                    messageText = text,
                ),
            )
        return WebhookIngestionResponse(
            status = if (recorded.duplicate) WebhookIngestionStatus.DUPLICATE else WebhookIngestionStatus.ACCEPTED,
            eventId = recorded.id.toString(),
            receivedAt = receivedAt,
            persistedAt = recorded.persistedAt,
        )
    }

    private fun required(
        value: String?,
        field: String,
    ): String = value?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$field is required")

    private fun bounded(
        value: String,
        field: String,
        maximum: Int,
    ): String {
        require(value.length <= maximum) { "$field exceeds $maximum characters" }
        return value
    }

    private companion object {
        const val INCOMING_MESSAGE_WEBHOOK = "incomingMessageReceived"
        const val TEXT_MESSAGE_TYPE = "textMessage"
        const val GROUP_CHAT_SUFFIX = "@g.us"
    }
}
