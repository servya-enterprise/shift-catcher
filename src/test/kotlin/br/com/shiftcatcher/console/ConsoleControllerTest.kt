package br.com.shiftcatcher.console

import br.com.shiftcatcher.availability.AvailabilityService
import br.com.shiftcatcher.availability.CommitmentListResponse
import br.com.shiftcatcher.availability.CommitmentResponse
import br.com.shiftcatcher.availability.CommitmentSource
import br.com.shiftcatcher.claim.ClaimListResponse
import br.com.shiftcatcher.claim.ClaimMessageResponse
import br.com.shiftcatcher.claim.ClaimMessageService
import br.com.shiftcatcher.claim.ClaimMode
import br.com.shiftcatcher.claim.ClaimResponse
import br.com.shiftcatcher.claim.ClaimService
import br.com.shiftcatcher.claim.ClaimStatus
import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.messaging.IncomingMessageListResponse
import br.com.shiftcatcher.messaging.IncomingMessageResponse
import br.com.shiftcatcher.messaging.IngestionService
import br.com.shiftcatcher.messaging.ProcessingStatus
import br.com.shiftcatcher.rules.OpportunityEvaluationService
import br.com.shiftcatcher.shift.ExtractionMethod
import br.com.shiftcatcher.shift.OpportunityStatus
import br.com.shiftcatcher.shift.ShiftOpportunityListResponse
import br.com.shiftcatcher.shift.ShiftOpportunityResponse
import br.com.shiftcatcher.shift.ShiftOpportunityService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The console renders on the server, so a broken template is a runtime failure rather than a
 * compile error. This slice actually renders every page.
 *
 * The assertion that matters most is the escaping one. These pages display text that strangers
 * wrote in a WhatsApp group; if that text could carry markup, the console would be an injection
 * vector aimed at the one person holding the admin token.
 */
@WebMvcTest(ConsoleController::class)
@EnableConfigurationProperties(ShiftCatcherProperties::class)
@TestPropertySource(properties = ["shift-catcher.security.admin-api-token=test-admin-token"])
class ConsoleControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var opportunityService: ShiftOpportunityService

    @MockitoBean
    private lateinit var evaluationService: OpportunityEvaluationService

    @MockitoBean
    private lateinit var claimService: ClaimService

    @MockitoBean
    private lateinit var claimMessageService: ClaimMessageService

    @MockitoBean
    private lateinit var availabilityService: AvailabilityService

    @MockitoBean
    private lateinit var ingestionService: IngestionService

    @BeforeEach
    fun stubs() {
        given(opportunityService.list())
            .willReturn(ShiftOpportunityListResponse(listOf(opportunity()), count = 1, limit = 100))
        given(opportunityService.detail(anyString())).willReturn(opportunity())
        given(claimService.list()).willReturn(ClaimListResponse(listOf(claim()), count = 1, limit = 100))
        given(claimMessageService.current())
            .willReturn(ClaimMessageResponse(message = "PEGO", version = 0, updatedAt = NOW))
        given(ingestionService.list())
            .willReturn(IncomingMessageListResponse(listOf(message()), count = 1, limit = 100))
        given(availabilityService.list(null, null)).willReturn(
            CommitmentListResponse(
                from = LocalDate.of(2026, 8, 24),
                to = LocalDate.of(2026, 10, 24),
                commitments = listOf(commitment()),
                count = 1,
            ),
        )
    }

    @Test
    fun `an anonymous visitor is sent to the sign-in page`() {
        mockMvc.get("/console").andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/console/login")
        }
        mockMvc.get("/console/claims").andExpect { redirectedUrl("/console/login") }
        mockMvc.get("/console/agenda").andExpect { redirectedUrl("/console/login") }
    }

    @Test
    fun `the wrong token does not open the door`() {
        val body =
            mockMvc
                .post("/console/login") { param("token", "not-the-token") }
                .andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString

        assertTrue("Token incorreto" in body)
    }

    @Test
    fun `every page renders for a signed-in operator`() {
        val signedIn = signIn()

        listOf("/console", "/console/claims", "/console/agenda", "/console/messages", "/console/settings")
            .forEach { path ->
                mockMvc.get(path) { session = signedIn }.andExpect {
                    status { isOk() }
                    content { contentTypeCompatibleWith("text/html") }
                }
            }
    }

    @Test
    fun `a message written by a stranger is shown as text, never as markup`() {
        val signedIn = signIn()

        val body =
            mockMvc
                .get("/console/messages") { session = signedIn }
                .andReturn()
                .response.contentAsString

        assertFalse(HOSTILE in body, "the raw script tag must never reach the page")
        assertTrue("&lt;script&gt;" in body, "it should be there, escaped, so she can read what was sent")
    }

    @Test
    fun `the opportunity list shows the offer and its original wording`() {
        val signedIn = signIn()

        val body =
            mockMvc
                .get("/console") { session = signedIn }
                .andReturn()
                .response.contentAsString

        assertTrue("25/08 19:00–07:00 (+1)" in body, "the window, in her timezone and her format")
        assertTrue("PS Central" in body)
        assertTrue("R$ 1.200,00" in body)
        assertTrue("Pegar" in body, "an ELIGIBLE offer is one tap away")
    }

    @Test
    fun `a post without the session token is refused`() {
        val signedIn = signIn()

        mockMvc
            .post("/console/opportunities/$OPPORTUNITY_ID/claim") { session = signedIn }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `a post with the session token goes through and redirects`() {
        val signedIn = signIn()
        given(claimService.claim(OPPORTUNITY_ID, null)).willReturn(claim())

        mockMvc
            .post("/console/opportunities/$OPPORTUNITY_ID/claim") {
                session = signedIn
                param(ConsoleSessionFilter.CSRF_FIELD, csrfTokenOf(signedIn))
            }.andExpect {
                // POST/redirect/GET: a reload must never claim the same shift twice.
                status { is3xxRedirection() }
                redirectedUrl("/console")
            }
    }

    @Test
    fun `signing out closes the session`() {
        val signedIn = signIn()

        mockMvc
            .post("/console/logout") {
                session = signedIn
                param(ConsoleSessionFilter.CSRF_FIELD, csrfTokenOf(signedIn))
            }.andExpect { redirectedUrl("/console/login") }

        mockMvc.get("/console") { session = signedIn }.andExpect { redirectedUrl("/console/login") }
    }

    /** Signs in for real, and hands back the session the sign-in created. */
    private fun signIn(): MockHttpSession {
        val result =
            mockMvc
                .post("/console/login") {
                    session = MockHttpSession()
                    param("token", "test-admin-token")
                }.andExpect { redirectedUrl("/console") }
                .andReturn()
        // Authentication replaces the session id, so the useful handle is the one login left behind.
        return result.request.session as MockHttpSession
    }

    private fun csrfTokenOf(session: MockHttpSession): String = session.getAttribute(ConsoleSessionFilter.CSRF_TOKEN) as String

    private fun opportunity(): ShiftOpportunityResponse =
        ShiftOpportunityResponse(
            id = OPPORTUNITY_ID,
            sourceMessageId = MESSAGE_ID,
            groupId = UUID.randomUUID().toString(),
            status = OpportunityStatus.ELIGIBLE,
            shiftDate = LocalDate.of(2026, 8, 25),
            startTime = LocalTime.of(19, 0),
            endTime = LocalTime.of(7, 0),
            endsNextDay = true,
            location = "PS Central",
            city = "Bauru",
            amount = BigDecimal("1200.00"),
            currency = "BRL",
            specialty = null,
            notes = null,
            extractionMethod = ExtractionMethod.DETERMINISTIC,
            confidence = BigDecimal.ONE,
            ambiguousFields = emptyList(),
            resolutionReason = null,
            reviewNote = null,
            version = 0,
            detectedAt = NOW,
            extractionCompletedAt = NOW,
        )

    private fun claim(): ClaimResponse =
        ClaimResponse(
            id = UUID.randomUUID().toString(),
            opportunityId = OPPORTUNITY_ID,
            status = ClaimStatus.CLAIMED,
            mode = ClaimMode.MANUAL,
            chatId = "120363000000000000@g.us",
            quotedMessageId = "offer-1",
            message = "PEGO",
            providerMessageId = "provider-out-1",
            attemptCount = 1,
            decidedAt = NOW,
            claimedAt = NOW,
            failedAt = null,
            failureCode = null,
            attempts = emptyList(),
        )

    private fun message(): IncomingMessageResponse =
        IncomingMessageResponse(
            id = MESSAGE_ID,
            eventId = UUID.randomUUID().toString(),
            groupId = UUID.randomUUID().toString(),
            providerMessageId = "offer-1",
            chatId = "120363000000000000@g.us",
            chatName = "Plantoes",
            senderId = "5511999999999@c.us",
            senderName = "Colega",
            text = HOSTILE,
            providerTimestamp = NOW,
            receivedAt = NOW,
            persistedAt = NOW,
            processingStatus = ProcessingStatus.PROCESSED,
            ignoredReason = null,
        )

    private fun commitment(): CommitmentResponse =
        CommitmentResponse(
            source = CommitmentSource.MANUAL,
            reference = UUID.randomUUID().toString(),
            label = "Santa Casa",
            shiftDate = LocalDate.of(2026, 9, 3),
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(13, 0),
            endsNextDay = false,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-24T21:00:00Z")
        val OPPORTUNITY_ID: String = UUID.randomUUID().toString()
        val MESSAGE_ID: String = UUID.randomUUID().toString()

        /** What a hostile participant would post if the console rendered raw HTML. */
        const val HOSTILE = "Plantao amanha <script>alert('x')</script>"
    }
}
