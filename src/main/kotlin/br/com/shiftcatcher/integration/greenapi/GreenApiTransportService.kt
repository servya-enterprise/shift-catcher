package br.com.shiftcatcher.integration.greenapi

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.foundation.http.ApiProblemException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

@Service
class GreenApiTransportService(
    private val properties: ShiftCatcherProperties,
    private val instanceHealth: WhatsAppInstanceHealth,
    private val messageSender: WhatsAppMessageSender,
    private val eventRepository: IncomingProviderEventRepository,
    private val replyRepository: TransportTestReplyRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun state(): GreenApiStateResponse {
        if (!properties.greenApi.isProviderConfigured()) {
            return GreenApiStateResponse(
                configured = false,
                state = "UNCONFIGURED",
                rawState = null,
                operational = false,
                observedAt = clock.instant(),
            )
        }
        val health = providerCall { instanceHealth.getState() }
        return health.toResponse(configured = true)
    }

    fun verify(): GreenApiVerificationResponse {
        val state = state()
        val latest = eventRepository.latestGroupMessage()
        return GreenApiVerificationResponse(
            configured = state.configured,
            providerState = state.state,
            providerOperational = state.operational,
            latestGroupWebhook = latest?.toResponse(),
            correctIdentifiersObserved = latest != null,
            readyForTestReply = state.operational && latest != null,
            quotedReplyVisualStatus = "NOT_CONFIRMED",
            verified = false,
            checkedAt = clock.instant(),
        )
    }

    fun sendTestReply(
        request: SendTestReplyRequest,
        idempotencyKey: String?,
    ): SendTestReplyResponse {
        val key = requiredBounded(idempotencyKey, "Idempotency-Key", 128)
        val chatId = requiredBounded(request.chatId, "chatId", 128)
        require(chatId.endsWith("@g.us")) { "chatId must identify a group" }
        val quotedMessageId = requiredBounded(request.quotedMessageId, "quotedMessageId", 128)
        val logicalKey = logicalKey(chatId, quotedMessageId)

        replyRepository.findExisting(key, logicalKey)?.let { existing ->
            return replayOrConflict(existing)
        }

        val health =
            if (properties.greenApi.isProviderConfigured()) {
                providerCall { instanceHealth.getState() }
            } else {
                throw instanceNotOperational("GREEN-API is not configured")
            }
        if (!health.operational) {
            throw instanceNotOperational("GREEN-API instance is ${health.rawState}")
        }

        val reserved = replyRepository.reserve(key, logicalKey, chatId, quotedMessageId)
        if (!reserved.created) {
            return replayOrConflict(reserved)
        }

        val sendStartedAt = clock.instant()
        replyRepository.markSendStarted(reserved.record.id, sendStartedAt)
        try {
            val receipt =
                providerCall {
                    messageSender.sendQuotedMessage(
                        SendQuotedMessage(
                            chatId = chatId,
                            message = CLAIM_TEXT,
                            quotedMessageId = quotedMessageId,
                        ),
                    )
                }
            return replyRepository
                .markAccepted(reserved.record.id, receipt.providerMessageId, receipt.acceptedAt)
                .toResponse(idempotentReplay = false)
        } catch (exception: RuntimeException) {
            val failureCode = (exception as? ApiProblemException)?.code ?: "GREEN_API_UNAVAILABLE"
            replyRepository.markFailed(reserved.record.id, failureCode, clock.instant())
            throw exception
        }
    }

    private fun replayOrConflict(reserved: ReservedTestReply): SendTestReplyResponse {
        if (reserved.keyCollision) {
            throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Idempotency conflict",
                message = "Idempotency-Key was already used for a different test reply",
            )
        }
        return reserved.record.toResponse(idempotentReplay = true)
    }

    private fun <T> providerCall(block: () -> T): T =
        try {
            block()
        } catch (exception: GreenApiNotConfiguredException) {
            throw instanceNotOperational("GREEN-API is not configured")
        } catch (exception: GreenApiTransportException) {
            val status =
                if (exception.kind == GreenApiFailureKind.TIMEOUT) {
                    HttpStatus.GATEWAY_TIMEOUT
                } else {
                    HttpStatus.BAD_GATEWAY
                }
            throw ApiProblemException(
                status = status,
                code = "GREEN_API_UNAVAILABLE",
                title = "GREEN-API unavailable",
                message = exception.message ?: "GREEN-API request failed",
            )
        }

    private fun instanceNotOperational(detail: String): ApiProblemException =
        ApiProblemException(
            status = HttpStatus.CONFLICT,
            code = "INSTANCE_NOT_OPERATIONAL",
            title = "Instance not operational",
            message = detail,
        )

    private fun requiredBounded(
        value: String?,
        field: String,
        maximum: Int,
    ): String {
        val result = value?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$field is required")
        require(result.length <= maximum) { "$field exceeds $maximum characters" }
        return result
    }

    private fun logicalKey(
        chatId: String,
        quotedMessageId: String,
    ): String {
        val source = "$chatId\u0000$quotedMessageId\u0000$CLAIM_TEXT"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun GreenApiInstanceHealth.toResponse(configured: Boolean): GreenApiStateResponse =
        GreenApiStateResponse(
            configured = configured,
            state = state.name,
            rawState = rawState,
            operational = operational,
            observedAt = observedAt,
        )

    private fun LatestProviderEvent.toResponse(): LatestGroupWebhookResponse =
        LatestGroupWebhookResponse(
            eventId = id.toString(),
            chatId = chatId,
            senderId = senderId,
            providerMessageId = providerMessageId,
            messageText = messageText,
            providerTimestamp = providerTimestamp,
            webhookReceivedAt = webhookReceivedAt,
            persistedAt = persistedAt,
        )

    private fun TestReplyRecord.toResponse(idempotentReplay: Boolean): SendTestReplyResponse =
        SendTestReplyResponse(
            replyId = id.toString(),
            status = status,
            providerMessageId = providerMessageId,
            chatId = chatId,
            quotedMessageId = quotedMessageId,
            message = CLAIM_TEXT,
            sendStartedAt = sendStartedAt,
            providerAcceptedAt = providerAcceptedAt,
            idempotentReplay = idempotentReplay,
            visualConfirmationRequired = true,
        )

    private companion object {
        const val CLAIM_TEXT = "PEGO"
    }
}

data class GreenApiStateResponse(
    val configured: Boolean,
    val state: String,
    val rawState: String?,
    val operational: Boolean,
    val observedAt: Instant,
)

data class GreenApiVerificationResponse(
    val configured: Boolean,
    val providerState: String,
    val providerOperational: Boolean,
    val latestGroupWebhook: LatestGroupWebhookResponse?,
    val correctIdentifiersObserved: Boolean,
    val readyForTestReply: Boolean,
    val quotedReplyVisualStatus: String,
    val verified: Boolean,
    val checkedAt: Instant,
)

data class LatestGroupWebhookResponse(
    val eventId: String,
    val chatId: String,
    val senderId: String,
    val providerMessageId: String,
    val messageText: String,
    val providerTimestamp: Instant,
    val webhookReceivedAt: Instant,
    val persistedAt: Instant,
)

data class SendTestReplyRequest(
    val chatId: String? = null,
    val quotedMessageId: String? = null,
)

data class SendTestReplyResponse(
    val replyId: String,
    val status: TestReplyStatus,
    val providerMessageId: String?,
    val chatId: String,
    val quotedMessageId: String,
    val message: String,
    val sendStartedAt: Instant?,
    val providerAcceptedAt: Instant?,
    val idempotentReplay: Boolean,
    val visualConfirmationRequired: Boolean,
)
