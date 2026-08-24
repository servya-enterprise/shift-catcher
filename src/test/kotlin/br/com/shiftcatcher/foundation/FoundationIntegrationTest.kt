package br.com.shiftcatcher.foundation

import br.com.shiftcatcher.PostgresTestConfiguration
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

@Import(PostgresTestConfiguration::class)
@SpringBootTest(properties = ["shift-catcher.security.admin-api-token=test-admin-token"])
@AutoConfigureMockMvc
class FoundationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `Flyway migration applies to clean PostgreSQL`() {
        val foundationMigrationCount =
            jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success",
                Int::class.java,
            )
        val markerCount = jdbcTemplate.queryForObject("select count(*) from poc_bootstrap", Int::class.java)

        assertEquals(1, foundationMigrationCount)
        assertEquals(1, markerCount)
    }

    @Test
    fun `health reports application and PostgreSQL ready`() {
        mockMvc
            .get("/api/v1/health") {
                header("Authorization", "Bearer test-admin-token")
                header("X-Correlation-Id", "foundation-test")
            }.andExpect {
                status { isOk() }
                header { string("X-Correlation-Id", "foundation-test") }
                content { contentType("application/json") }
                jsonPath("$.status") { value("UP") }
                jsonPath("$.database") { value("UP") }
            }
    }

    @Test
    fun `status reports the transport gate as evidenced and ingestion as current`() {
        mockMvc
            .get("/api/v1/poc/status") {
                header("Authorization", "Bearer test-admin-token")
            }.andExpect {
                status { isOk() }
                header { string("X-Correlation-Id", matchesPattern("[0-9a-f-]{36}")) }
                jsonPath("$.status") { value("INGESTION_IN_PROGRESS") }
                jsonPath("$.currentWorkPackage") { value("WP-POC-003") }
                jsonPath("$.greenApiTransport") { value("VERIFIED") }
            }
    }

    @Test
    fun `admin endpoints reject missing bearer as Problem Details`() {
        mockMvc
            .get("/api/v1/health")
            .andExpect {
                status { isUnauthorized() }
                content { contentTypeCompatibleWith("application/problem+json") }
                jsonPath("$.status") { value(401) }
                jsonPath("$.correlationId") { exists() }
                jsonPath("$.instance") { value("/api/v1/health") }
            }
    }

    @Test
    fun `bearer scheme is case insensitive`() {
        mockMvc
            .get("/api/v1/health") {
                header("Authorization", "bearer test-admin-token")
            }.andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `GREEN API state is explicitly unconfigured without credentials`() {
        mockMvc
            .get("/api/v1/integrations/green-api/state") {
                header("Authorization", "Bearer test-admin-token")
            }.andExpect {
                status { isOk() }
                jsonPath("$.configured") { value(false) }
                jsonPath("$.state") { value("UNCONFIGURED") }
                jsonPath("$.operational") { value(false) }
            }
    }
}
