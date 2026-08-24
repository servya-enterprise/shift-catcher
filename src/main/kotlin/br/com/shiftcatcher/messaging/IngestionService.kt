package br.com.shiftcatcher.messaging

import br.com.shiftcatcher.detection.AnalyzeMessageCommand
import br.com.shiftcatcher.detection.MessageAnalysisService
import br.com.shiftcatcher.foundation.http.ApiProblemException
import br.com.shiftcatcher.group.AllowedGroupRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Ingestion stage of the pipeline: persist the provider event, normalize the message, and apply the
 * allowlist gate. Detection, extraction, rules and claims are later work packages, so the furthest
 * this stage moves an event is `PENDING` — never `PROCESSED`.
 */
@Service
class IngestionService(
    private val eventRepository: IncomingProviderEventRepository,
    private val messageRepository: IncomingMessageRepository,
    private val groupRepository: AllowedGroupRepository,
    private val analysisService: MessageAnalysisService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun ingest(
        message: IncomingTransportMessage,
        correlationId: String?,
        payloadHash: String?,
    ): IngestionResult {
        val recorded = eventRepository.record(message, correlationId, payloadHash)
        if (recorded.duplicate) {
            // A redelivered webhook must not repeat effects, so the stored decision is replayed as-is.
            val existing = messageRepository.findByProviderEventId(recorded.id)
            return IngestionResult(
                duplicate = true,
                eventId = recorded.id,
                messageId = existing?.id,
                persistedAt = recorded.persistedAt,
                processingStatus = existing?.processingStatus ?: ProcessingStatus.RECEIVED,
                ignoredReason = existing?.ignoredReason,
            )
        }

        val decision = gate(message.chatId)
        val normalizedText = normalize(message.messageText)
        val messageId =
            messageRepository.upsert(
                NormalizedMessage(
                    providerEventId = recorded.id,
                    groupId = decision.groupId,
                    providerMessageId = message.providerMessageId,
                    chatId = message.chatId,
                    chatName = message.chatName,
                    senderId = message.senderId,
                    senderName = message.senderName,
                    text = normalizedText,
                    providerTimestamp = message.providerTimestamp,
                    receivedAt = message.webhookReceivedAt,
                ),
            )

        // Detection and deterministic extraction are cheap and side-effect free, so they run inside
        // the webhook transaction. The AI fallback is explicitly withheld here: the webhook contract
        // forbids a model call inside the request.
        val analysis =
            if (decision.status == ProcessingStatus.PENDING) {
                analysisService.analyze(
                    AnalyzeMessageCommand(
                        messageId = messageId,
                        groupId = decision.groupId,
                        text = normalizedText,
                        messageTimestamp = message.providerTimestamp,
                        allowAiFallback = false,
                    ),
                )
            } else {
                null
            }
        val status = if (analysis != null) ProcessingStatus.PROCESSED else decision.status
        eventRepository.updateProcessing(recorded.id, status, decision.reason, clock.instant())
        return IngestionResult(
            duplicate = false,
            eventId = recorded.id,
            messageId = messageId,
            persistedAt = recorded.persistedAt,
            processingStatus = status,
            ignoredReason = decision.reason,
            candidate = analysis?.candidate,
            opportunityId = analysis?.opportunity?.id,
        )
    }

    fun list(): IncomingMessageListResponse {
        val messages = messageRepository.findRecent(MAX_LIST_SIZE).map { it.toResponse() }
        return IncomingMessageListResponse(messages = messages, count = messages.size, limit = MAX_LIST_SIZE)
    }

    fun detail(messageId: String): IncomingMessageResponse = load(messageId).toResponse()

    /**
     * Re-applies the allowlist gate to a stored message. This is what makes registering a group after
     * the fact useful: the messages already captured from it can be promoted from `IGNORED` to
     * `PENDING` without asking the provider to redeliver anything. Repeating the call is a no-op.
     */
    @Transactional
    fun reprocess(messageId: String): ReprocessResponse {
        val record = load(messageId)
        val decision = gate(record.chatId)
        val reprocessedAt = clock.instant()

        // Outside the webhook request the AI fallback is allowed, so this is also the entry point
        // that can resolve a message the deterministic parser left ambiguous.
        val analysis =
            if (decision.status == ProcessingStatus.PENDING) {
                analysisService.analyze(
                    AnalyzeMessageCommand(
                        messageId = record.id,
                        groupId = decision.groupId,
                        text = record.text,
                        messageTimestamp = record.providerTimestamp,
                        allowAiFallback = true,
                    ),
                )
            } else {
                null
            }
        val status = if (analysis != null) ProcessingStatus.PROCESSED else decision.status
        val changed =
            status != record.processingStatus ||
                decision.reason != record.ignoredReason ||
                decision.groupId != record.groupId
        if (changed) {
            messageRepository.updateGroup(record.id, decision.groupId)
        }
        eventRepository.updateProcessing(record.providerEventId, status, decision.reason, reprocessedAt)
        return ReprocessResponse(
            messageId = record.id.toString(),
            groupId = decision.groupId?.toString(),
            processingStatus = status,
            ignoredReason = decision.reason,
            changed = changed,
            candidate = analysis?.candidate,
            opportunityId = analysis?.opportunity?.id?.toString(),
            reprocessedAt = reprocessedAt,
        )
    }

    private fun gate(chatId: String): GateDecision {
        val group = groupRepository.findByProviderChatId(chatId)
        return when {
            group == null -> GateDecision(ProcessingStatus.IGNORED, IgnoredReason.GROUP_NOT_ALLOWLISTED, null)
            !group.enabled -> GateDecision(ProcessingStatus.IGNORED, IgnoredReason.GROUP_DISABLED, group.id)
            else -> GateDecision(ProcessingStatus.PENDING, null, group.id)
        }
    }

    /** Collapses whitespace runs so later stages match on a predictable single-spaced text. */
    private fun normalize(text: String): String = text.trim().replace(WHITESPACE_RUN, " ")

    private fun load(messageId: String): IncomingMessageRecord =
        messageRepository.findById(parseId(messageId))
            ?: throw ApiProblemException(
                status = HttpStatus.NOT_FOUND,
                code = "RESOURCE_NOT_FOUND",
                title = "Message not found",
                message = "No incoming message matches the supplied identifier",
            )

    private fun parseId(messageId: String): UUID =
        runCatching { UUID.fromString(messageId) }
            .getOrElse { throw IllegalArgumentException("messageId must be a UUID") }

    private fun IncomingMessageRecord.toResponse(): IncomingMessageResponse =
        IncomingMessageResponse(
            id = id.toString(),
            eventId = providerEventId.toString(),
            groupId = groupId?.toString(),
            providerMessageId = providerMessageId,
            chatId = chatId,
            chatName = chatName,
            senderId = senderId,
            senderName = senderName,
            text = text,
            providerTimestamp = providerTimestamp,
            receivedAt = receivedAt,
            persistedAt = persistedAt,
            processingStatus = processingStatus,
            ignoredReason = ignoredReason,
        )

    private companion object {
        const val MAX_LIST_SIZE = 100
        val WHITESPACE_RUN = Regex("\\s+")
    }
}

private data class GateDecision(
    val status: ProcessingStatus,
    val reason: IgnoredReason?,
    val groupId: UUID?,
)

data class IngestionResult(
    val duplicate: Boolean,
    val eventId: UUID,
    val messageId: UUID?,
    val persistedAt: Instant,
    val processingStatus: ProcessingStatus,
    val ignoredReason: IgnoredReason?,
    val candidate: Boolean? = null,
    val opportunityId: UUID? = null,
)

data class IncomingMessageResponse(
    val id: String,
    val eventId: String,
    val groupId: String?,
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

data class IncomingMessageListResponse(
    val messages: List<IncomingMessageResponse>,
    val count: Int,
    val limit: Int,
)

data class ReprocessResponse(
    val messageId: String,
    val groupId: String?,
    val processingStatus: ProcessingStatus,
    val ignoredReason: IgnoredReason?,
    val changed: Boolean,
    val candidate: Boolean? = null,
    val opportunityId: String? = null,
    val reprocessedAt: Instant,
)
