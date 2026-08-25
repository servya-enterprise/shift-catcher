package br.com.shiftcatcher.benchmark

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The corpus of `08-Quality/Benchmark-Plan.md`: real messages with what a human says they mean.
 *
 * Either the text is supplied directly, or a `messageId` points at something already in the log -
 * which is where the honest corpus comes from, because the messages this has to read are the ones
 * that actually arrived.
 */
data class BenchmarkRequest(
    val label: String? = null,
    val cases: List<BenchmarkCase>? = null,
)

data class BenchmarkCase(
    /** How this case is named in the report, so a failure can be looked up in the corpus. */
    val reference: String? = null,
    val text: String? = null,
    val messageId: String? = null,
    val messageTimestamp: String? = null,
    val expected: BenchmarkExpectation? = null,
)

/**
 * What a human says the message means. Only `candidate` is required: for the seventy-odd messages
 * that are not offers at all, that is the entire truth about them.
 */
data class BenchmarkExpectation(
    val candidate: Boolean? = null,
    val shiftDate: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val amount: BigDecimal? = null,
    val location: String? = null,
    val city: String? = null,
    /**
     * True when a person could not read this one either. The pipeline is *right* to leave such a
     * message ambiguous, and wrong to answer it confidently.
     */
    val ambiguous: Boolean? = null,
)

data class BenchmarkStartResponse(
    val benchmarkId: String,
    val status: BenchmarkStatus,
    val corpusSize: Int,
    val aiEnabled: Boolean,
    val startedAt: Instant,
)

data class BenchmarkRunResponse(
    val benchmarkId: String,
    val status: BenchmarkStatus,
    val label: String?,
    val corpusSize: Int,
    val aiEnabled: Boolean,
    val startedAt: Instant,
    val completedAt: Instant?,
    val failure: String?,
    val report: BenchmarkReport?,
)

enum class BenchmarkStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}

data class BenchmarkReport(
    val corpus: CorpusShape,
    val detection: DetectionScore,
    val extraction: ExtractionScore,
    val safety: SafetyScore,
    val latency: LatencyScore,
    val criteria: List<CriterionOutcome>,
    /** Every case the run got wrong, so the corpus can be read rather than guessed at. */
    val misses: List<CaseMiss>,
    val slowest: List<CaseTiming>,
)

/** Whether the corpus itself meets the minimum the plan demands. A thin corpus proves little. */
data class CorpusShape(
    val size: Int,
    val expectedCandidates: Int,
    val expectedStructured: Int,
    val expectedAmbiguous: Int,
    val meetsPlanMinimum: Boolean,
    val shortfalls: List<String>,
)

data class DetectionScore(
    val truePositives: Int,
    val falsePositives: Int,
    val trueNegatives: Int,
    val falseNegatives: Int,
    val precision: Double?,
    val recall: Double?,
)

data class ExtractionScore(
    val scoredCases: Int,
    val fields: List<FieldScore>,
    val aiInvoked: Int,
)

data class FieldScore(
    val field: String,
    val expected: Int,
    val correct: Int,
    val wrong: Int,
    val missing: Int,
    val accuracy: Double?,
)

/**
 * The number the GO/NO-GO actually turns on.
 *
 * `confidentlyWrong` counts messages the pipeline read with no ambiguity left - so a permissive rule
 * set would have let it answer on its own - and got a field wrong anyway. Every one of those is a
 * `PEGO` sent for a shift that was not what it seemed.
 */
data class SafetyScore(
    val autoClaimable: Int,
    val confidentlyWrong: Int,
    val autoClaimableWithAmbiguousField: Int,
    val ambiguousHeldForReview: Int,
    val ambiguousAnsweredConfidently: Int,
)

data class LatencyScore(
    val samples: Int,
    val p50Ms: Double?,
    val p95Ms: Double?,
    val p99Ms: Double?,
    val maxMs: Long?,
)

/**
 * One line of `08-Quality/Benchmark-Plan.md`, and what this run can say about it.
 *
 * `NOT_MEASURABLE_HERE` is used rather than omitted on purpose: a criterion silently missing from a
 * report reads as a criterion met.
 */
data class CriterionOutcome(
    val criterion: String,
    val outcome: CriterionResult,
    val detail: String,
)

enum class CriterionResult {
    MET,
    NOT_MET,
    NOT_MEASURABLE_HERE,
}

data class CaseMiss(
    val reference: String,
    val kind: String,
    val detail: String,
)

data class CaseTiming(
    val reference: String,
    val elapsedMs: Long,
    val aiInvoked: Boolean,
)
