package br.com.shiftcatcher.benchmark

import br.com.shiftcatcher.ai.AiParseRequest
import br.com.shiftcatcher.ai.AiParseResult
import br.com.shiftcatcher.ai.AiShiftParserPort
import br.com.shiftcatcher.detection.DetectionResultRepository
import br.com.shiftcatcher.detection.MessageAnalysisService
import br.com.shiftcatcher.detection.MessageDetector
import br.com.shiftcatcher.extraction.ShiftExtractor
import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.messaging.IngestionService
import br.com.shiftcatcher.rules.RuleEngine
import br.com.shiftcatcher.shift.ShiftOpportunityRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The scoring itself, with the real detector, the real extractor and the real rule engine.
 *
 * Only the database is faked. What this asserts is the arithmetic the GO/NO-GO would rest on, and
 * the property that matters more than any accuracy number: that the report cannot quietly report
 * success for something it never measured.
 */
class BenchmarkScoringTest {
    private val properties =
        ShiftCatcherProperties(
            detection =
                ShiftCatcherProperties.Detection(
                    knownLocations = listOf("PS Central"),
                    knownCities = listOf("Bauru"),
                    timezone = ZoneId.of("America/Sao_Paulo"),
                ),
        )
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val repository = RecordingRepository()

    private val service =
        BenchmarkService(
            repository = repository,
            analysisService =
                MessageAnalysisService(
                    detector = MessageDetector(properties),
                    extractor = ShiftExtractor(properties),
                    aiParser = DisabledAiParser,
                    // The preview path touches neither repository; the pipeline persists nothing.
                    detectionResultRepository = mock(DetectionResultRepository::class.java),
                    opportunityRepository = mock(ShiftOpportunityRepository::class.java),
                    properties = properties,
                    clock = CLOCK,
                ),
            ingestionService = mock(IngestionService::class.java),
            ruleEngine = RuleEngine(),
            aiParser = DisabledAiParser,
            properties = properties,
            objectMapper = objectMapper,
            clock = CLOCK,
        )

    @Test
    fun `a clean offer is detected, read and counted as correct`() {
        val report =
            run(
                BenchmarkCase(
                    reference = "clean",
                    text = "Plantao 25/08 19-07 no PS Central R$ 1.200",
                    messageTimestamp = MOMENT,
                    expected =
                        BenchmarkExpectation(
                            candidate = true,
                            shiftDate = LocalDate.of(2026, 8, 25),
                            startTime = LocalTime.of(19, 0),
                            endTime = LocalTime.of(7, 0),
                            amount = BigDecimal("1200.00"),
                            location = "PS Central",
                        ),
                ),
            )

        assertEquals(1, report.detection.truePositives)
        assertEquals(0, report.safety.confidentlyWrong, "nothing was answered confidently and wrongly")
        assertTrue(report.misses.isEmpty(), "no misses, got ${report.misses}")
    }

    @Test
    fun `a message that is not an offer must not be flagged`() {
        val report =
            run(
                BenchmarkCase(
                    reference = "chatter",
                    text = "bom dia pessoal, alguem sabe onde fica o refeitorio?",
                    messageTimestamp = MOMENT,
                    expected = BenchmarkExpectation(candidate = false),
                ),
            )

        assertEquals(1, report.detection.trueNegatives)
        assertEquals(0, report.detection.falsePositives)
    }

    @Test
    fun `a reading that disagrees with the corpus is reported, and named as such when it was confident`() {
        val report =
            run(
                BenchmarkCase(
                    reference = "wrong-amount",
                    text = "Plantao 25/08 19-07 no PS Central R$ 1.200",
                    messageTimestamp = MOMENT,
                    expected =
                        BenchmarkExpectation(
                            candidate = true,
                            // The corpus says nine hundred; the message says one thousand two hundred.
                            amount = BigDecimal("900.00"),
                        ),
                ),
            )

        assertEquals(1, report.safety.confidentlyWrong)
        val miss = report.misses.single()
        assertEquals("CONFIDENTLY_WRONG", miss.kind)
        assertTrue("amount" in miss.detail)

        val criterion = report.criteria.single { it.criterion.startsWith("nothing is answered") }
        assertEquals(CriterionResult.NOT_MET, criterion.outcome)
    }

    @Test
    fun `nothing ambiguous is ever auto-claimable`() {
        // A candidate with no readable date or hours: the fail-safe of DEC-005 must hold it back.
        val report =
            run(
                BenchmarkCase(
                    reference = "vague",
                    text = "tem vaga de plantao alguem quer",
                    messageTimestamp = MOMENT,
                    expected = BenchmarkExpectation(candidate = true, ambiguous = true),
                ),
            )

        assertEquals(0, report.safety.autoClaimableWithAmbiguousField)
        assertEquals(1, report.safety.ambiguousHeldForReview)
        assertEquals(0, report.safety.ambiguousAnsweredConfidently)
        assertEquals(
            CriterionResult.MET,
            report.criteria.single { it.criterion.startsWith("zero auto-claim") }.outcome,
        )
    }

    @Test
    fun `a thin corpus is called thin instead of being scored as if it were enough`() {
        val report = run(BenchmarkCase(reference = "only-one", text = "Plantao 25/08 19-07", expected = null))

        assertEquals(false, report.corpus.meetsPlanMinimum)
        assertEquals(4, report.corpus.shortfalls.size, "all four minimums are short: ${report.corpus.shortfalls}")
        assertEquals(
            CriterionResult.NOT_MET,
            report.criteria.single { it.criterion.startsWith("corpus meets") }.outcome,
        )
    }

    @Test
    fun `what this run cannot measure is stated, not omitted`() {
        val report = run(BenchmarkCase(reference = "any", text = "Plantao 25/08 19-07", expected = null))

        val unmeasurable = report.criteria.filter { it.outcome == CriterionResult.NOT_MEASURABLE_HERE }
        // Sending, concurrency and the human confirmation are all outside a corpus replay. A report
        // that dropped them would read as though they had passed.
        assertEquals(3, unmeasurable.size, "expected the three it cannot answer, got ${unmeasurable.map { it.criterion }}")
        assertTrue(unmeasurable.any { "provider-accepted" in it.criterion })
        assertTrue(unmeasurable.any { "duplicate" in it.criterion })
        assertTrue(unmeasurable.any { "real group" in it.criterion })
    }

    @Test
    fun `the run reports latency for every case it scored`() {
        val report =
            run(
                BenchmarkCase(reference = "a", text = "Plantao 25/08 19-07 no PS Central", expected = null),
                BenchmarkCase(reference = "b", text = "bom dia", expected = null),
            )

        assertEquals(2, report.latency.samples)
        assertNotNull(report.latency.p95Ms)
        assertEquals(2, report.slowest.size)
    }

    @Test
    fun `a corpus must say where it came from, and an invented one cannot support a GO`() {
        val refused =
            runCatching {
                service.start(
                    BenchmarkRequest(cases = listOf(BenchmarkCase(text = "Plantao 25/08 19-07"))),
                )
            }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException, "provenance has no default, on purpose")

        val report = run(BenchmarkCase(reference = "invented", text = "Plantao 25/08 19-07", expected = null))
        assertEquals(CorpusProvenance.SYNTHETIC, report.corpus.provenance)
        assertEquals(false, report.corpus.admissibleAsGoEvidence)
        assertEquals(
            CriterionResult.NOT_MET,
            report.criteria.single { it.criterion.startsWith("the corpus is the wording") }.outcome,
        )
    }

    /**
     * The shipped corpus, all hundred of it, against the real pipeline.
     *
     * It asserts the invariant rather than the accuracy. Accuracy numbers from invented messages are
     * not worth freezing - they only say how well the parser handles the phrasings whoever wrote the
     * corpus imagined. The fail-safe is worth freezing: across a hundred varied messages, nothing
     * that stayed ambiguous may ever become claimable without a human.
     */
    @Test
    fun `across the whole corpus, nothing ambiguous is ever claimable unattended`() {
        val corpus =
            objectMapper.readValue(
                java.io.File("08-Quality/corpus/synthetic-v1.json"),
                BenchmarkRequest::class.java,
            )
        service.start(corpus)
        val report = objectMapper.readValue(repository.awaitReport(), BenchmarkReport::class.java)

        assertEquals(100, report.corpus.size)
        assertEquals(0, report.safety.autoClaimableWithAmbiguousField)
        assertEquals(false, report.corpus.admissibleAsGoEvidence, "invented messages cannot support a GO")
    }

    private fun run(vararg cases: BenchmarkCase): BenchmarkReport {
        service.start(
            BenchmarkRequest(label = "test", provenance = CorpusProvenance.SYNTHETIC, cases = cases.toList()),
        )
        val json = repository.awaitReport()
        return objectMapper.readValue(json, BenchmarkReport::class.java)
    }

    /** Stands in for the table, and lets the test wait for the off-thread run to land. */
    private class RecordingRepository : BenchmarkRepository(mock(org.springframework.jdbc.core.JdbcTemplate::class.java)) {
        private val latch = java.util.concurrent.CountDownLatch(1)

        @Volatile
        private var reportJson: String? = null

        @Volatile
        private var failure: String? = null

        override fun start(
            label: String?,
            provenance: CorpusProvenance,
            corpusSize: Int,
            aiEnabled: Boolean,
            startedAt: Instant,
        ): BenchmarkRun =
            BenchmarkRun(
                id = UUID.randomUUID(),
                status = BenchmarkStatus.RUNNING,
                label = label,
                provenance = provenance,
                corpusSize = corpusSize,
                aiEnabled = aiEnabled,
                startedAt = startedAt,
                completedAt = null,
                failure = null,
                reportJson = null,
            )

        override fun complete(
            id: UUID,
            reportJson: String,
            completedAt: Instant,
        ) {
            this.reportJson = reportJson
            latch.countDown()
        }

        override fun fail(
            id: UUID,
            failure: String,
            completedAt: Instant,
        ) {
            this.failure = failure
            latch.countDown()
        }

        fun awaitReport(): String {
            check(latch.await(AWAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) { "the benchmark never finished" }
            failure?.let { error("the benchmark failed: $it") }
            return reportJson ?: error("the benchmark completed without a report")
        }

        private companion object {
            const val AWAIT_SECONDS = 30L
        }
    }

    private object DisabledAiParser : AiShiftParserPort {
        override fun isEnabled(): Boolean = false

        override fun parse(request: AiParseRequest): AiParseResult = error("the model must not be called when disabled")
    }

    private companion object {
        const val MOMENT = "2026-08-24T21:00:00Z"
        val CLOCK: Clock = Clock.fixed(Instant.parse(MOMENT), ZoneId.of("UTC"))
    }
}
