package br.com.shiftcatcher.integration.greenapi

data class GreenApiWebhookEnvelope(
    val typeWebhook: String? = null,
    val instanceData: InstanceData? = null,
    val timestamp: Long? = null,
    val idMessage: String? = null,
    val senderData: SenderData? = null,
    val messageData: MessageData? = null,
) {
    data class InstanceData(
        val idInstance: Long? = null,
    )

    data class SenderData(
        val chatId: String? = null,
        val chatName: String? = null,
        val sender: String? = null,
        val senderName: String? = null,
        val senderContactName: String? = null,
    )

    data class MessageData(
        val typeMessage: String? = null,
        val textMessageData: TextMessageData? = null,
    )

    data class TextMessageData(
        val textMessage: String? = null,
    )
}

data class WebhookIngestionResponse(
    val status: WebhookIngestionStatus,
    val eventId: String? = null,
    val receivedAt: java.time.Instant,
    val persistedAt: java.time.Instant? = null,
)

enum class WebhookIngestionStatus {
    ACCEPTED,
    DUPLICATE,
    IGNORED,
}
