package br.com.shiftcatcher.benchmark

import br.com.shiftcatcher.PostgresTestConfiguration
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `EP-035`/`EP-036` end to end.
 *
 * The property asserted first is the one that would be catastrophic to get wrong: replaying a
 * corpus of real shift offers must not create a single opportunity or claim. A benchmark that
 * answered the offers it was measuring would take shifts in a real group of colleagues.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    webEnvironment = WebEnvironment.MOCK,
    properties = [
        "shift-catcher.security.admin-api-token=test-admin-token",
        "shift-catcher.detection.known-locations[0]=PS Central",
        "shift-catcher.claim.worker-enabled=false",
    ],
)
@AutoConfigureMockMvc
class BenchmarkIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun reset() {
        jdbcTemplate.update("delete from benchmark_run")
        jdbcTemplate.update("delete from shift_opportunity")
        jdbcTemplate.update("delete from detection_result")
    }

    @Test
    fun `the benchmark endpoints require the admin bearer token`() {
        mockMvc.post("/api/v1/poc/benchmark/start").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a corpus is replayed without creating a single opportunity`() {
        val benchmarkId = start(TWO_CASES)

        val report = awaitReport(benchmarkId)
        assertEquals("COMPLETED", JsonPath.read<String>(report, "$.status"))
        assertEquals(2, JsonPath.read<Int>(report, "$.corpusSize"))
        assertEquals("SYNTHETIC", JsonPath.read<String>(report, "$.provenance"))
        assertEquals(false, JsonPath.read<Boolean>(report, "$.report.corpus.admissibleAsGoEvidence"))
        assertEquals(1, JsonPath.read<Int>(report, "$.report.detection.truePositives"))
        assertEquals(1, JsonPath.read<Int>(report, "$.report.detection.trueNegatives"))

        // The whole point: the pipeline ran, and left nothing behind.
        assertEquals(0, countOf("shift_opportunity"))
        assertEquals(0, countOf("detection_result"))
        assertEquals(0, countOf("shift_claim"))
    }

    @Test
    fun `the report says the corpus is too thin to decide on`() {
        val report = awaitReport(start(TWO_CASES))

        assertEquals(false, JsonPath.read<Boolean>(report, "$.report.corpus.meetsPlanMinimum"))
        val criteria = JsonPath.read<List<String>>(report, "$.report.criteria[*].outcome")
        assertTrue("NOT_MEASURABLE_HERE" in criteria, "the criteria it cannot answer stay in the report")
    }

    @Test
    fun `a corpus that names a message nobody has is refused before anything starts`() {
        mockMvc
            .post("/api/v1/poc/benchmark/start") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {"provenance":"SYNTHETIC","cases":[{"reference":"ghost","messageId":"00000000-0000-0000-0000-000000000000"}]}
                    """.trimIndent()
            }.andExpect { status { isNotFound() } }

        // Refused at the door, so nothing occupies the single-run slot.
        assertEquals(0, countOf("benchmark_run"))
    }

    @Test
    fun `a corpus that does not say where it came from is refused`() {
        mockMvc
            .post("/api/v1/poc/benchmark/start") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"cases":[{"text":"Plantao 25/08 19-07"}]}"""
            }.andExpect { status { isBadRequest() } }

        assertEquals(0, countOf("benchmark_run"))
    }

    @Test
    fun `an empty corpus is refused`() {
        mockMvc
            .post("/api/v1/poc/benchmark/start") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = """{"provenance":"SYNTHETIC","cases":[]}"""
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `only one benchmark runs at a time`() {
        // Planted directly rather than raced: the guard is the partial unique index, and this asserts
        // the index rather than the timing of a fast run.
        jdbcTemplate.update(
            """
            insert into benchmark_run (status, label, provenance, corpus_size, ai_enabled, started_at)
            values ('RUNNING', 'planted', 'SYNTHETIC', 1, false, current_timestamp)
            """.trimIndent(),
        )

        mockMvc
            .post("/api/v1/poc/benchmark/start") {
                adminBearer()
                contentType = MediaType.APPLICATION_JSON
                content = TWO_CASES
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("CONFLICT") }
            }
    }

    @Test
    fun `an unknown benchmark is a 404`() {
        mockMvc
            .get("/api/v1/poc/benchmark/00000000-0000-0000-0000-000000000000") { adminBearer() }
            .andExpect { status { isNotFound() } }
    }

    private fun start(body: String): String {
        val response =
            mockMvc
                .post("/api/v1/poc/benchmark/start") {
                    adminBearer()
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString
        return JsonPath.read(response, "$.benchmarkId")
    }

    /** The run is off-thread by design, so the report is polled rather than awaited inline. */
    private fun awaitReport(benchmarkId: String): String {
        repeat(POLL_ATTEMPTS) {
            val body =
                mockMvc
                    .get("/api/v1/poc/benchmark/$benchmarkId") { adminBearer() }
                    .andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString
            if (JsonPath.read<String>(body, "$.status") != "RUNNING") {
                return body
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("the benchmark did not finish within ${POLL_ATTEMPTS * POLL_INTERVAL_MS} ms")
    }

    private fun countOf(table: String): Int = jdbcTemplate.queryForObject("select count(*) from $table", Int::class.java) ?: 0

    private fun MockHttpServletRequestDsl.adminBearer() {
        header("Authorization", "Bearer test-admin-token")
    }

    private companion object {
        const val POLL_ATTEMPTS = 60
        const val POLL_INTERVAL_MS = 100L

        val TWO_CASES =
            """
            {
              "label": "smoke",
              "provenance": "SYNTHETIC",
              "cases": [
                {
                  "reference": "offer",
                  "text": "Plantao 25/08 19-07 no PS Central R$ 1.200",
                  "messageTimestamp": "2026-08-24T21:00:00Z",
                  "expected": {"candidate": true, "shiftDate": "2026-08-25", "startTime": "19:00"}
                },
                {
                  "reference": "chatter",
                  "text": "bom dia pessoal",
                  "messageTimestamp": "2026-08-24T21:00:00Z",
                  "expected": {"candidate": false}
                }
              ]
            }
            """.trimIndent()
    }
}
