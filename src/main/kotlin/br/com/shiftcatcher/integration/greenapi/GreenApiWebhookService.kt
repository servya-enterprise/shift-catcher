package br.com.shiftcatcher.integration.greenapi

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.messaging.IncomingTransportMessage
import br.com.shiftcatcher.messaging.IngestionService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Translates the GREEN-API envelope into the provider-agnostic message the ingestion stage consumes.
 * Anything this instance is not supposed to act on is acknowledged as `IGNORED` instead of failing:
 * a 4xx would make the provider redeliver the same unusable payload indefinitely.
 */
@Service
class GreenApiWebhookService(
    private val ingestionService: IngestionService,
    private val properties: ShiftCatcherProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun ingest(
        envelope: GreenApiWebhookEnvelope,
        receivedAt: Instant,
        correlationId: String?,
        payloadHash: String?,
    ): WebhookIngestionResponse {
        if (envelope.typeWebhook != INCOMING_MESSAGE_WEBHOOK) {
            return ignored(receivedAt)
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
        val senderId = required(senderData.sender, "senderData.sender")

        // Direct chats are outside the frozen POC scope; nothing about them is persisted.
        if (!chatId.endsWith(GROUP_CHAT_SUFFIX)) {
            return ignored(receivedAt)
        }

        val messageData = envelope.messageData ?: throw IllegalArgumentException("messageData is required")
        if (messageData.typeMessage != TEXT_MESSAGE_TYPE) {
            return ignored(receivedAt)
        }
        val text = required(messageData.textMessageData?.textMessage, "messageData.textMessageData.textMessage")

        val result =
            ingestionService.ingest(
                message =
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
                correlationId = correlationId,
                payloadHash = payloadHash,
            )

        return WebhookIngestionResponse(
            status = if (result.duplicate) WebhookIngestionStatus.DUPLICATE else WebhookIngestionStatus.ACCEPTED,
            eventId = result.eventId.toString(),
            messageId = result.messageId?.toString(),
            receivedAt = receivedAt,
            persistedAt = result.persistedAt,
            processingStatus = result.processingStatus.name,
            ignoredReason = result.ignoredReason?.name,
        )
    }

    private fun ignored(receivedAt: Instant): WebhookIngestionResponse =
        WebhookIngestionResponse(
            status = WebhookIngestionStatus.IGNORED,
            receivedAt = receivedAt,
        )

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
