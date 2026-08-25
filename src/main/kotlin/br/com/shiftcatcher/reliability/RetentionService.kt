package br.com.shiftcatcher.reliability

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.observability.AuditEventWrite
import br.com.shiftcatcher.observability.AuditRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/**
 * Retention, in two different shapes for two different reasons.
 *
 * **Redaction** for the message chain. `shift_claim` references `shift_opportunity` references
 * `incoming_message` references `incoming_provider_event`, and a claim is the record of a message
 * that really went into a group - deleting it would erase evidence of something the world still
 * remembers. The dedupe key matters too: drop an old event row and a redelivered webhook stops being
 * recognised as a duplicate. So the rows stay and the words go. That is also the better answer for
 * the people in those groups, who never consented to anything and are not users.
 *
 * **Deletion** for what stands alone: audit rows, spent outbox intents, old benchmark reports.
 * Nothing references them and nothing is proved by keeping them for ever.
 *
 * It defaults to a dry run. This is the only code in the project that destroys data, it cannot be
 * exercised against a real database on the machine where it was written, and a deploy that quietly
 * erased six months of messages would be unrecoverable. So it reports what it would do, and someone
 * reads that before arming it.
 */
@Component
class RetentionService(
    private val jdbcTemplate: JdbcTemplate,
    private val auditRepository: AuditRepository,
    private val properties: ShiftCatcherProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun runOnce(): RetentionSummary {
        val retention = properties.retention
        if (!retention.enabled) {
            return RetentionSummary(dryRun = true, skipped = true)
        }
        val now = clock.instant()
        val summary =
            RetentionSummary(
                dryRun = retention.dryRun,
                skipped = false,
                messagesRedacted = redactMessages(now.minus(retention.messageContent), retention.dryRun),
                eventsRedacted = redactProviderEvents(now.minus(retention.messageContent), retention.dryRun),
                auditDeleted = deleteAudit(now.minus(retention.auditTrail), retention.dryRun),
                outboxDeleted = deleteSpentOutbox(now.minus(retention.spentOutbox), retention.dryRun),
                benchmarksDeleted = deleteBenchmarks(now.minus(retention.benchmarkReports), retention.dryRun),
            )

        if (summary.touchedAnything()) {
            val verb = if (retention.dryRun) "would touch" else "touched"
            logger.info("Retention {} {}", verb, summary)
            auditRepository.record(
                AuditEventWrite(
                    aggregateType = "RETENTION",
                    aggregateId = null,
                    eventType = if (retention.dryRun) "RETENTION_DRY_RUN" else "RETENTION_APPLIED",
                    detail = summary.toString().take(MAX_DETAIL),
                    occurredAt = now,
                ),
            )
        }
        return summary
    }

    /**
     * The normalised text and who wrote it. `chat_id` and `provider_message_id` stay: they are how
     * the row is found and deduplicated, and neither is a sentence anybody wrote.
     */
    @Transactional
    fun redactMessages(
        before: Instant,
        dryRun: Boolean,
    ): Int =
        apply(
            dryRun = dryRun,
            count = "select count(*) from incoming_message where received_at < ? and redacted_at is null",
            change =
                """
                update incoming_message
                   set text = '$PLACEHOLDER',
                       sender_id = '$PLACEHOLDER',
                       sender_name = null,
                       redacted_at = current_timestamp,
                       updated_at = current_timestamp
                 where received_at < ? and redacted_at is null
                """.trimIndent(),
            before = before,
        )

    @Transactional
    fun redactProviderEvents(
        before: Instant,
        dryRun: Boolean,
    ): Int =
        apply(
            dryRun = dryRun,
            count = "select count(*) from incoming_provider_event where webhook_received_at < ? and redacted_at is null",
            change =
                """
                update incoming_provider_event
                   set message_text = '$PLACEHOLDER',
                       sender_id = '$PLACEHOLDER',
                       sender_name = null,
                       sender_contact_name = null,
                       redacted_at = current_timestamp
                 where webhook_received_at < ? and redacted_at is null
                """.trimIndent(),
            before = before,
        )

    @Transactional
    fun deleteAudit(
        before: Instant,
        dryRun: Boolean,
    ): Int =
        apply(
            dryRun = dryRun,
            count = "select count(*) from audit_event where occurred_at < ?",
            change = "delete from audit_event where occurred_at < ?",
            before = before,
        )

    /**
     * Spent send intents. Deleting them does not reopen the "one logical send" guard: a second claim
     * for the same opportunity is refused by the unique constraint on `shift_claim.opportunity_id`
     * long before an outbox row would be consulted, and a retracted claim is refused for the same
     * reason. Only `DONE` rows go; anything pending or failed is still work.
     */
    @Transactional
    fun deleteSpentOutbox(
        before: Instant,
        dryRun: Boolean,
    ): Int =
        apply(
            dryRun = dryRun,
            count = "select count(*) from outbox_event where status = 'DONE' and completed_at < ?",
            change = "delete from outbox_event where status = 'DONE' and completed_at < ?",
            before = before,
        )

    @Transactional
    fun deleteBenchmarks(
        before: Instant,
        dryRun: Boolean,
    ): Int =
        apply(
            dryRun = dryRun,
            count = "select count(*) from benchmark_run where started_at < ? and status <> 'RUNNING'",
            change = "delete from benchmark_run where started_at < ? and status <> 'RUNNING'",
            before = before,
        )

    /** Counts first, and only writes when armed. The count is the whole value of a dry run. */
    private fun apply(
        dryRun: Boolean,
        count: String,
        change: String,
        before: Instant,
    ): Int {
        val stamp = Timestamp.from(before)
        val affected = jdbcTemplate.queryForObject(count, Int::class.java, stamp) ?: 0
        if (dryRun || affected == 0) {
            return affected
        }
        return jdbcTemplate.update(change, stamp)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(RetentionService::class.java)
        const val PLACEHOLDER = "REDACTED"
        const val MAX_DETAIL = 512
    }
}

data class RetentionSummary(
    val dryRun: Boolean,
    val skipped: Boolean,
    val messagesRedacted: Int = 0,
    val eventsRedacted: Int = 0,
    val auditDeleted: Int = 0,
    val outboxDeleted: Int = 0,
    val benchmarksDeleted: Int = 0,
) {
    fun touchedAnything(): Boolean = messagesRedacted + eventsRedacted + auditDeleted + outboxDeleted + benchmarksDeleted > 0
}

/**
 * Daily, and on its own scheduler thread. Before `SCHEDULER_POOL_SIZE` this would have been one more
 * job queueing in front of the outbox poller.
 */
@Component
@ConditionalOnProperty(
    prefix = "shift-catcher.claim",
    name = ["worker-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class RetentionScheduler(
    private val service: RetentionService,
) {
    @Scheduled(
        initialDelayString = "\${shift-catcher.retention.initial-delay-ms:120000}",
        fixedDelayString = "\${shift-catcher.retention.interval-ms:86400000}",
    )
    fun run() {
        runCatching { service.runOnce() }
            .onFailure { failure -> LoggerFactory.getLogger(RetentionScheduler::class.java).error("Retention pass failed", failure) }
    }
}
