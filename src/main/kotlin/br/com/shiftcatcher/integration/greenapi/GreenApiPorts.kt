package br.com.shiftcatcher.integration.greenapi

import java.time.Instant

interface WhatsAppInstanceHealth {
    fun getState(): GreenApiInstanceHealth
}

interface WhatsAppMessageSender {
    fun sendQuotedMessage(command: SendQuotedMessage): ProviderSendReceipt
}

/**
 * Deleting is a separate capability from sending: `03-Integrations/Green-API-Contract.md` only ever
 * needed to send, and a retraction is a compensating action rather than part of the happy path.
 * The provider deletes for everyone only within 60 hours of the original send.
 */
interface WhatsAppMessageRetractor {
    fun deleteMessage(command: DeleteMessage)
}

data class DeleteMessage(
    val chatId: String,
    val providerMessageId: String,
)

data class SendQuotedMessage(
    val chatId: String,
    val message: String,
    val quotedMessageId: String,
)

data class ProviderSendReceipt(
    val providerMessageId: String,
    val acceptedAt: Instant,
)

data class GreenApiInstanceHealth(
    val state: GreenApiInstanceState,
    val rawState: String,
    val observedAt: Instant,
) {
    val operational: Boolean = state == GreenApiInstanceState.AUTHORIZED
}

enum class GreenApiInstanceState {
    AUTHORIZED,
    STARTING,
    SLEEP_MODE,
    NOT_AUTHORIZED,
    BLOCKED,
    SUSPENDED,
    UNKNOWN,
    ;

    companion object {
        fun fromProvider(rawState: String): GreenApiInstanceState =
            when (rawState) {
                "authorized" -> AUTHORIZED
                "starting" -> STARTING
                "sleepMode" -> SLEEP_MODE
                "notAuthorized" -> NOT_AUTHORIZED
                "blocked" -> BLOCKED
                "suspended", "yellowCard" -> SUSPENDED
                else -> UNKNOWN
            }
    }
}

enum class GreenApiFailureKind {
    CLIENT_ERROR,
    SERVER_ERROR,
    TIMEOUT,
    INVALID_RESPONSE,
}

class GreenApiNotConfiguredException : RuntimeException("GREEN-API is not configured")

class GreenApiTransportException(
    val kind: GreenApiFailureKind,
    message: String,
) : RuntimeException(message)
