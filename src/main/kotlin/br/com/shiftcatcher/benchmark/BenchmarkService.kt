package br.com.shiftcatcher.benchmark

import br.com.shiftcatcher.ai.AiShiftParserPort
import br.com.shiftcatcher.detection.MessageAnalysisService
import br.com.shiftcatcher.detection.ResolvedShift
import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.foundation.http.ApiProblemException
import br.com.shiftcatcher.messaging.IngestionService
import br.com.shiftcatcher.rules.EvaluationContext
import br.com.shiftcatcher.rules.RuleDefinition
import br.com.shiftcatcher.rules.RuleEngine
import br.com.shiftcatcher.shift.OpportunityStatus
import br.com.shiftcatcher.shift.ShiftOpportunity
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `EP-035`/`EP-036`, the harness `WP-POC-008` needs in order to run at all.
 *
 * It replays a labelled corpus through the same detector, extractor and model the live pipeline
 * uses, and persists nothing: no opportunity, no claim, no message. That is not a convenience.
 * A benchmark that produced claims would answer real shift offers in a real group of colleagues
 * while measuring itself.
 *
 * It also does not decide. `08-Quality/POC-Acceptance-Test.md` ends in `GO`,
 * `GO_WITH_LIMITATIONS` or `NO_GO`, and that verdict is a person's. This reports the facts each
 * criterion turns on, including - explicitly - the criteria it cannot speak to at all.
 */
@Service
class BenchmarkService(
    private val repository: BenchmarkRepository,
    private val analysisService: MessageAnalysisService,
    private val ingestionService: IngestionService,
    private val ruleEngine: RuleEngine,
    private val aiParser: AiShiftParserPort,
    private val properties: ShiftCatcherProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Its own thread, deliberately not the shared scheduler. Everything background in this
     * application runs on one thread today (`12-MVP/MVP-Scope.md`), and a benchmark that invokes the
     * model can occupy it for minutes - which would stall the claims this exists to protect.
     */
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "benchmark").apply { isDaemon = true } }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
        executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
    }

    /** `EP-035`. Returns as soon as the corpus is understood; the run itself happens off-thread. */
    fun start(request: BenchmarkRequest?): BenchmarkStartResponse {
        val cases = request?.cases?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("cases is required")
        require(cases.size <= MAX_CASES) { "a corpus of more than $MAX_CASES cases is not accepted" }
        // No default. A run whose provenance was assumed is a run whose conclusion is unsafe.
        val provenance =
            request.provenance
                ?: throw IllegalArgumentException(
                    "provenance is required: say whether these are REAL messages, SYNTHETIC ones, or MIXED",
                )

        // Resolved before the run starts so a corpus that references a message nobody has fails
        // immediately, with a readable error, instead of half-way through a run.
        val prepared = cases.mapIndexed { index, case -> prepare(case, index) }
        val aiEnabled = aiParser.isEnabled()

        val run =
            repository.start(
                label = request.label?.take(MAX_LABEL),
                provenance = provenance,
                corpusSize = prepared.size,
                aiEnabled = aiEnabled,
                startedAt = clock.instant(),
            ) ?: throw ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "A benchmark is already running",
                message = "Only one benchmark runs at a time; wait for the current one to finish",
            )

        executor.submit { execute(run.id, prepared, provenance) }
        return BenchmarkStartResponse(
            benchmarkId = run.id.toString(),
            status = run.status,
            provenance = run.provenance,
            corpusSize = run.corpusSize,
            aiEnabled = run.aiEnabled,
            startedAt = run.startedAt,
        )
    }

    /** `EP-036`. */
    fun detail(benchmarkId: String): BenchmarkRunResponse {
        val run =
            repository.findById(parseId(benchmarkId))
                ?: throw ApiProblemException(
                    status = HttpStatus.NOT_FOUND,
                    code = "RESOURCE_NOT_FOUND",
                    title = "Benchmark not found",
                    message = "No benchmark run matches the supplied identifier",
                )
        return BenchmarkRunResponse(
            benchmarkId = run.id.toString(),
            status = run.status,
            provenance = run.provenance,
            label = run.label,
            corpusSize = run.corpusSize,
            aiEnabled = run.aiEnabled,
            startedAt = run.startedAt,
            completedAt = run.completedAt,
            failure = run.failure,
            report = run.reportJson?.let { objectMapper.readValue(it, BenchmarkReport::class.java) },
        )
    }

    private fun execute(
        runId: UUID,
        cases: List<PreparedCase>,
        provenance: CorpusProvenance,
    ) {
        runCatching {
            val scored = cases.map { score(it) }
            val report = report(cases, scored, provenance)
            repository.complete(runId, objectMapper.writeValueAsString(report), clock.instant())
        }.onFailure { failure ->
            logger.error("Benchmark {} failed", runId, failure)
            // Recorded rather than swallowed: a failed run must release the single-active slot and
            // say why, or the next attempt only sees a mysterious 409.
            repository.fail(runId, failure.message ?: failure::class.java.name, clock.instant())
        }
    }

    private fun score(case: PreparedCase): ScoredCase {
        val startedAt = System.nanoTime()
        val preview = analysisService.preview(case.text, case.messageTimestamp)
        val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

        val resolved = preview.resolved
        val autoClaimAllowed = resolved != null && permissiveOutcomeAllows(resolved, case.messageTimestamp)
        return ScoredCase(
            case = case,
            candidate = preview.detection.candidate,
            resolved = resolved,
            autoClaimAllowed = autoClaimAllowed,
            aiInvoked = resolved?.aiInvoked ?: false,
            elapsedMs = elapsedMs,
            contradicted = disagreements(case.expected, resolved, absent = false),
            unread = disagreements(case.expected, resolved, absent = true),
        )
    }

    /**
     * Asks the real rule engine, under the most permissive rule set it accepts, whether this reading
     * could have been claimed without a human.
     *
     * Permissive on purpose: the question is not what today's rule set filters out by preference,
     * but whether anything could get past the fail-safe of `DEC-005` while still ambiguous. Asking
     * the engine rather than restating its rule here is the difference between verifying the guard
     * and assuming it.
     */
    private fun permissiveOutcomeAllows(
        resolved: ResolvedShift,
        messageTimestamp: Instant,
    ): Boolean =
        ruleEngine
            .evaluate(
                EvaluationContext(
                    opportunity = syntheticOpportunity(resolved),
                    definition = RuleDefinition(autoClaimEnabled = true, allowAutoClaimFromAi = true),
                    groupEnabled = true,
                    groupAutoClaimEnabled = true,
                    messageTimestamp = messageTimestamp,
                    instanceOperational = true,
                    now = messageTimestamp,
                    timezone = properties.detection.timezone,
                ),
            ).autoClaimAllowed

    /** Never persisted. It exists only to be judged. */
    private fun syntheticOpportunity(resolved: ResolvedShift): ShiftOpportunity =
        ShiftOpportunity(
            id = SYNTHETIC_ID,
            sourceMessageId = SYNTHETIC_ID,
            groupId = null,
            status = OpportunityStatus.EVALUATING,
            shiftDate = resolved.shiftDate,
            startTime = resolved.startTime,
            endTime = resolved.endTime,
            endsNextDay = resolved.endsNextDay,
            location = resolved.location,
            city = resolved.city,
            amount = resolved.amount,
            currency = resolved.currency,
            specialty = resolved.specialty,
            notes = resolved.notes,
            extractionMethod = resolved.method,
            confidence = resolved.confidence,
            ambiguousFields = resolved.ambiguousFields,
            resolutionReason = resolved.reason,
            reviewNote = null,
            version = 0,
            detectedAt = SYNTHETIC_MOMENT,
            extractionCompletedAt = SYNTHETIC_MOMENT,
        )

    /**
     * Fields where the reading and the corpus part company. Only fields the corpus actually asserts
     * are compared: silence in a label is not a claim about the message.
     *
     * [absent] selects which kind of parting: a value the pipeline never found, or a value it found
     * and got wrong. Keeping them apart is the whole point - one is a gap, the other is a lie.
     */
    private fun disagreements(
        expected: BenchmarkExpectation?,
        resolved: ResolvedShift?,
        absent: Boolean,
    ): List<String> {
        if (expected == null) return emptyList()
        return SCORED_FIELDS.filter { field ->
            val want = expectedValue(expected, field) ?: return@filter false
            val got = resolved?.let { resolvedValue(it, field) }
            if (got == null) absent else !absent && !matches(want, got)
        }
    }

    private fun matches(
        want: Any,
        got: Any,
    ): Boolean =
        when {
            want is BigDecimal && got is BigDecimal -> want.compareTo(got) == 0
            want is String && got is String -> want.equals(got, ignoreCase = true)
            else -> want == got
        }

    private fun report(
        cases: List<PreparedCase>,
        scored: List<ScoredCase>,
        provenance: CorpusProvenance,
    ): BenchmarkReport {
        val expectedCandidates = cases.count { it.expected?.candidate == true }
        val expectedAmbiguous = cases.count { it.expected?.ambiguous == true }
        val expectedStructured = cases.count { it.expected?.candidate == true && it.expected.ambiguous != true }

        val truePositives = scored.count { it.case.expected?.candidate == true && it.candidate }
        val falseNegatives = scored.count { it.case.expected?.candidate == true && !it.candidate }
        val falsePositives = scored.count { it.case.expected?.candidate == false && it.candidate }
        val trueNegatives = scored.count { it.case.expected?.candidate == false && !it.candidate }

        val confidentlyWrong = scored.count { it.autoClaimAllowed && it.contradicted.isNotEmpty() }
        val confidentlyIncomplete = scored.count { it.autoClaimAllowed && it.unread.isNotEmpty() }
        val autoWithAmbiguous =
            scored.count { it.autoClaimAllowed && (it.resolved?.ambiguousFields?.isNotEmpty() == true) }
        val ambiguousCases = scored.filter { it.case.expected?.ambiguous == true }
        val heldForReview = ambiguousCases.count { it.resolved?.ambiguousFields?.isNotEmpty() == true }

        val shortfalls = shortfalls(cases.size, expectedCandidates, expectedStructured, expectedAmbiguous)
        val elapsed = scored.map { it.elapsedMs }.sorted()

        return BenchmarkReport(
            corpus =
                CorpusShape(
                    provenance = provenance,
                    admissibleAsGoEvidence = provenance == CorpusProvenance.REAL && shortfalls.isEmpty(),
                    size = cases.size,
                    expectedCandidates = expectedCandidates,
                    expectedStructured = expectedStructured,
                    expectedAmbiguous = expectedAmbiguous,
                    meetsPlanMinimum = shortfalls.isEmpty(),
                    shortfalls = shortfalls,
                ),
            detection =
                DetectionScore(
                    truePositives = truePositives,
                    falsePositives = falsePositives,
                    trueNegatives = trueNegatives,
                    falseNegatives = falseNegatives,
                    precision = ratio(truePositives, truePositives + falsePositives),
                    recall = ratio(truePositives, truePositives + falseNegatives),
                ),
            extraction =
                ExtractionScore(
                    scoredCases = scored.count { it.case.expected?.candidate == true },
                    fields = fieldScores(scored),
                    aiInvoked = scored.count { it.aiInvoked },
                ),
            safety =
                SafetyScore(
                    autoClaimable = scored.count { it.autoClaimAllowed },
                    confidentlyWrong = confidentlyWrong,
                    confidentlyIncomplete = confidentlyIncomplete,
                    autoClaimableWithAmbiguousField = autoWithAmbiguous,
                    ambiguousHeldForReview = heldForReview,
                    ambiguousAnsweredConfidently = ambiguousCases.size - heldForReview,
                ),
            latency =
                LatencyScore(
                    samples = elapsed.size,
                    p50Ms = percentile(elapsed, PERCENTILE_50),
                    p95Ms = percentile(elapsed, PERCENTILE_95),
                    p99Ms = percentile(elapsed, PERCENTILE_99),
                    maxMs = elapsed.lastOrNull(),
                ),
            criteria =
                criteria(confidentlyWrong, confidentlyIncomplete, autoWithAmbiguous, elapsed, shortfalls, provenance),
            misses = misses(scored),
            slowest =
                scored
                    .sortedByDescending { it.elapsedMs }
                    .take(TOP_OUTLIERS)
                    .map { CaseTiming(it.case.reference, it.elapsedMs, it.aiInvoked) },
        )
    }

    private fun criteria(
        confidentlyWrong: Int,
        confidentlyIncomplete: Int,
        autoWithAmbiguous: Int,
        elapsed: List<Long>,
        shortfalls: List<String>,
        provenance: CorpusProvenance,
    ): List<CriterionOutcome> {
        val p95 = percentile(elapsed, PERCENTILE_95)
        return listOf(
            CriterionOutcome(
                criterion = "the corpus is the wording that really arrived",
                outcome =
                    if (provenance == CorpusProvenance.REAL) CriterionResult.MET else CriterionResult.NOT_MET,
                detail =
                    if (provenance == CorpusProvenance.REAL) {
                        "real group messages"
                    } else {
                        "$provenance: invented messages can fail this system but cannot pass it, because " +
                            "they measure the phrasings whoever wrote them thought of. Useful as a " +
                            "regression floor and as a NO-GO detector; not admissible as GO evidence"
                    },
            ),
            CriterionOutcome(
                criterion = "corpus meets the minimum of 08-Quality/Benchmark-Plan.md",
                outcome = if (shortfalls.isEmpty()) CriterionResult.MET else CriterionResult.NOT_MET,
                detail = if (shortfalls.isEmpty()) "100/30/20/10 satisfied" else shortfalls.joinToString("; "),
            ),
            CriterionOutcome(
                criterion = "zero auto-claim with an essential field ambiguous",
                outcome = if (autoWithAmbiguous == 0) CriterionResult.MET else CriterionResult.NOT_MET,
                detail = "$autoWithAmbiguous of the corpus would have been claimed while still ambiguous",
            ),
            CriterionOutcome(
                criterion = "nothing is answered confidently and wrongly",
                outcome = if (confidentlyWrong == 0) CriterionResult.MET else CriterionResult.NOT_MET,
                detail =
                    "$confidentlyWrong reading(s) had no ambiguity left and still contradicted the corpus; " +
                        "each one is a PEGO sent for a shift that was not what it seemed. A further " +
                        "$confidentlyIncomplete were unattended with a stated field left unread, which is " +
                        "a gap rather than a lie - and stops being harmless once a rule depends on it",
            ),
            CriterionOutcome(
                criterion = "internal pipeline P95 within the 1s budget",
                outcome =
                    when {
                        p95 == null -> CriterionResult.NOT_MEASURABLE_HERE
                        p95 <= P95_BUDGET_MS -> CriterionResult.MET
                        else -> CriterionResult.NOT_MET
                    },
                detail = "detection plus extraction P95 was ${p95 ?: "unmeasured"} ms, budget $P95_BUDGET_MS ms",
            ),
            // The three below are deliberately present and unanswered. A criterion missing from a
            // report reads as a criterion met.
            CriterionOutcome(
                criterion = "P95 to provider-accepted within 1s",
                outcome = CriterionResult.NOT_MEASURABLE_HERE,
                detail = "this run sends nothing; EP-003 measures it from real claims",
            ),
            CriterionOutcome(
                criterion = "zero duplicate and zero wrong-group claims",
                outcome = CriterionResult.NOT_MEASURABLE_HERE,
                detail = "a property of the claim engine under concurrency, covered by its integration tests",
            ),
            CriterionOutcome(
                criterion = "the quoted reply was seen in the real group",
                outcome = CriterionResult.NOT_MEASURABLE_HERE,
                detail = "requires a person to look at WhatsApp; no HTTP result substitutes for it",
            ),
        )
    }

    private fun misses(scored: List<ScoredCase>): List<CaseMiss> =
        scored
            .flatMap { case ->
                buildList {
                    val expected = case.case.expected
                    if (expected?.candidate == true && !case.candidate) {
                        add(CaseMiss(case.case.reference, "MISSED_OFFER", "an offer the detector did not flag"))
                    }
                    if (expected?.candidate == false && case.candidate) {
                        add(CaseMiss(case.case.reference, "FALSE_ALARM", "flagged, but the corpus says it is not an offer"))
                    }
                    if (case.contradicted.isNotEmpty()) {
                        val kind = if (case.autoClaimAllowed) "CONFIDENTLY_WRONG" else "MISREAD"
                        add(
                            CaseMiss(
                                case.case.reference,
                                kind,
                                "contradicted the corpus on ${case.contradicted.joinToString(", ")}",
                            ),
                        )
                    }
                    if (case.unread.isNotEmpty()) {
                        add(
                            CaseMiss(
                                case.case.reference,
                                "UNREAD_FIELD",
                                "never found ${case.unread.joinToString(", ")}, which the corpus states",
                            ),
                        )
                    }
                    if (expected?.ambiguous == true && case.resolved?.ambiguousFields?.isEmpty() == true) {
                        add(
                            CaseMiss(
                                case.case.reference,
                                "FALSE_CONFIDENCE",
                                "a person could not read this one, and the pipeline was sure",
                            ),
                        )
                    }
                }
            }.take(MAX_MISSES)

    private fun fieldScores(scored: List<ScoredCase>): List<FieldScore> =
        SCORED_FIELDS.map { field ->
            val asserted = scored.filter { expectedValue(it.case.expected, field) != null }
            val missing = asserted.count { field in it.unread }
            val wrong = asserted.count { field in it.contradicted }
            val correct = asserted.size - missing - wrong
            FieldScore(
                field = field,
                expected = asserted.size,
                correct = correct,
                wrong = maxOf(wrong, 0),
                missing = missing,
                accuracy = ratio(correct, asserted.size),
            )
        }

    private fun expectedValue(
        expected: BenchmarkExpectation?,
        field: String,
    ): Any? =
        when (field) {
            "shiftDate" -> expected?.shiftDate
            "startTime" -> expected?.startTime
            "endTime" -> expected?.endTime
            "amount" -> expected?.amount
            "location" -> expected?.location
            else -> expected?.city
        }

    private fun resolvedValue(
        resolved: ResolvedShift,
        field: String,
    ): Any? =
        when (field) {
            "shiftDate" -> resolved.shiftDate
            "startTime" -> resolved.startTime
            "endTime" -> resolved.endTime
            "amount" -> resolved.amount
            "location" -> resolved.location
            else -> resolved.city
        }

    private fun shortfalls(
        size: Int,
        candidates: Int,
        structured: Int,
        ambiguous: Int,
    ): List<String> =
        buildList {
            if (size < MIN_CORPUS) add("$size messages, the plan asks for $MIN_CORPUS")
            if (candidates < MIN_CANDIDATES) add("$candidates candidates, the plan asks for $MIN_CANDIDATES")
            if (structured < MIN_STRUCTURED) add("$structured structured offers, the plan asks for $MIN_STRUCTURED")
            if (ambiguous < MIN_AMBIGUOUS) add("$ambiguous ambiguous messages, the plan asks for $MIN_AMBIGUOUS")
        }

    private fun prepare(
        case: BenchmarkCase,
        index: Int,
    ): PreparedCase {
        val reference = case.reference?.takeIf { it.isNotBlank() } ?: "case-${index + 1}"
        val fromLog = case.messageId?.takeIf { it.isNotBlank() }?.let { ingestionService.detail(it) }
        val text =
            case.text?.takeIf { it.isNotBlank() }
                ?: fromLog?.text
                ?: throw IllegalArgumentException("$reference needs either text or a messageId")
        val timestamp =
            case.messageTimestamp
                ?.let { raw ->
                    runCatching { Instant.parse(raw) }
                        .getOrElse { throw IllegalArgumentException("$reference has an unreadable messageTimestamp") }
                }
                ?: fromLog?.providerTimestamp
                ?: clock.instant()
        return PreparedCase(
            reference = reference,
            text = text,
            messageTimestamp = timestamp,
            expected = case.expected,
        )
    }

    private fun ratio(
        part: Int,
        whole: Int,
    ): Double? = if (whole == 0) null else part.toDouble() / whole

    /** Linear interpolation, matching the `percentile_cont` that `EP-003` reports. */
    private fun percentile(
        sorted: List<Long>,
        fraction: Double,
    ): Double? {
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted.first().toDouble()
        val position = fraction * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        val weight = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
    }

    private fun parseId(benchmarkId: String): UUID =
        runCatching { UUID.fromString(benchmarkId) }
            .getOrElse { throw IllegalArgumentException("benchmarkId must be a UUID") }

    private data class PreparedCase(
        val reference: String,
        val text: String,
        val messageTimestamp: Instant,
        val expected: BenchmarkExpectation?,
    )

    private data class ScoredCase(
        val case: PreparedCase,
        val candidate: Boolean,
        val resolved: ResolvedShift?,
        val autoClaimAllowed: Boolean,
        val aiInvoked: Boolean,
        val elapsedMs: Long,
        /** Read, and different from what the corpus says. */
        val contradicted: List<String>,
        /** Stated by the corpus and never found. */
        val unread: List<String>,
    )

    private companion object {
        val logger = LoggerFactory.getLogger(BenchmarkService::class.java)
        val SYNTHETIC_ID: UUID = UUID(0, 0)
        val SYNTHETIC_MOMENT: Instant = Instant.EPOCH
        val SCORED_FIELDS = listOf("shiftDate", "startTime", "endTime", "amount", "location", "city")

        const val MAX_CASES = 500
        const val MAX_LABEL = 128
        const val MAX_MISSES = 100
        const val TOP_OUTLIERS = 10
        const val NANOS_PER_MILLI = 1_000_000L
        const val SHUTDOWN_SECONDS = 5L
        const val P95_BUDGET_MS = 1000.0
        const val PERCENTILE_50 = 0.50
        const val PERCENTILE_95 = 0.95
        const val PERCENTILE_99 = 0.99

        // `08-Quality/Benchmark-Plan.md`.
        const val MIN_CORPUS = 100
        const val MIN_CANDIDATES = 30
        const val MIN_STRUCTURED = 20
        const val MIN_AMBIGUOUS = 10
    }
}
