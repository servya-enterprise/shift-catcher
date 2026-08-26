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
import br.com.shiftcatcher.foundation.http.ApiProblemException
import br.com.shiftcatcher.group.AllowedGroupListResponse
import br.com.shiftcatcher.group.AllowedGroupResponse
import br.com.shiftcatcher.group.AllowedGroupService
import br.com.shiftcatcher.messaging.IncomingMessageListResponse
import br.com.shiftcatcher.messaging.IncomingMessageResponse
import br.com.shiftcatcher.messaging.IngestionService
import br.com.shiftcatcher.messaging.ProcessingStatus
import br.com.shiftcatcher.reliability.ProviderHealthGate
import br.com.shiftcatcher.reliability.ProviderHealthObservation
import br.com.shiftcatcher.rules.OpportunityEvaluationService
import br.com.shiftcatcher.shift.ExtractionMethod
import br.com.shiftcatcher.shift.OpportunityStatus
import br.com.shiftcatcher.shift.ReviewOpportunityRequest
import br.com.shiftcatcher.shift.ShiftOpportunityListResponse
import br.com.shiftcatcher.shift.ShiftOpportunityResponse
import br.com.shiftcatcher.shift.ShiftOpportunityService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JSON front door, exercised through the real session filter.
 *
 * This is the test the plan for this module could not be written without. Every screen in the
 * operator app depends on one thing that no in-memory double can demonstrate: that the rewritten
 * filter authenticates a fetch, refuses one with a status a client can branch on, and hands back a
 * CSRF token the app can still obtain after a page reload. Build the app against a double and the
 * defect surfaces on integration day, which is the day the only real user was going to use it.
 */
@WebMvcTest(ConsoleApiController::class)
@EnableConfigurationProperties(ShiftCatcherProperties::class)
@TestPropertySource(properties = ["shift-catcher.security.admin-api-token=test-admin-token"])
class ConsoleApiControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
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

    @MockitoBean
    private lateinit var groupService: AllowedGroupService

    @MockitoBean
    private lateinit var providerHealth: ProviderHealthGate

    @BeforeEach
    fun stubs() {
        given(opportunityService.list()).willReturn(
            ShiftOpportunityListResponse(
                listOf(
                    opportunity(OpportunityStatus.ELIGIBLE),
                    opportunity(OpportunityStatus.REVIEW_REQUIRED, id = WAITING_ID),
                    opportunity(OpportunityStatus.REJECTED, id = CLOSED_ID),
                ),
                count = 3,
                limit = 100,
            ),
        )
        given(opportunityService.detail(anyString())).willReturn(opportunity(OpportunityStatus.ELIGIBLE))
        given(ingestionService.list())
            .willReturn(IncomingMessageListResponse(listOf(message()), count = 1, limit = 100))
        given(ingestionService.detail(anyString())).willReturn(message())
        given(claimService.list()).willReturn(ClaimListResponse(listOf(claim()), count = 1, limit = 100))
        given(claimMessageService.current())
            .willReturn(ClaimMessageResponse(message = "PEGO", version = 0, updatedAt = NOW))
        given(availabilityService.list(null, null)).willReturn(
            CommitmentListResponse(
                from = LocalDate.of(2026, 8, 25),
                to = LocalDate.of(2026, 10, 25),
                commitments = listOf(manualCommitment(), claimCommitment()),
                count = 2,
            ),
        )
        given(groupService.list()).willReturn(AllowedGroupListResponse(listOf(group()), count = 1))
        given(providerHealth.current()).willReturn(healthy())
        // Matched by value rather than by a matcher: ArgumentMatchers.any() hands back null, and
        // Kotlin refuses to pass it where a non-null observation is expected.
        given(providerHealth.isFresh(healthy())).willReturn(true)
    }

    // --- the handshake ------------------------------------------------------

    @Test
    fun `an unauthenticated fetch is refused with json, never with a redirect`() {
        // The whole reason this filter branch exists. A redirect is followed transparently by the
        // browser, so the app would receive 200 with a sign-in page in the body and no way to tell
        // that from real data.
        mockMvc.get("/console/api/board").andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("AUTHENTICATION_REQUIRED") }
        }
    }

    @Test
    fun `the wrong token is refused without saying whether a token is configured`() {
        val body =
            mockMvc
                .post("/console/api/session") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"token":"not-the-token"}"""
                }.andExpect {
                    status { isUnauthorized() }
                    jsonPath("$.code") { value("INVALID_REQUEST") }
                }.andReturn()
                .response.contentAsString

        assertTrue("test-admin-token" !in body, "the expected token must never appear in a response")
    }

    @Test
    fun `signing in hands back a csrf token, because the cookie is HttpOnly`() {
        val session = MockHttpSession()
        mockMvc
            .post("/console/api/session") {
                this.session = session
                contentType = MediaType.APPLICATION_JSON
                content = """{"token":"test-admin-token"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.authenticated") { value(true) }
                jsonPath("$.csrfToken") { isNotEmpty() }
            }
    }

    @Test
    fun `the session survives a page reload with its token intact`() {
        // Without this endpoint the operator stays signed in for hours while the app forgets the
        // token it was handed at sign-in, and every action starts failing with 403. Her only
        // remedy would be to sign out and back in after each refresh.
        val signedIn = signIn()
        val token = csrfTokenOf(signedIn)

        mockMvc.get("/console/api/session") { session = signedIn }.andExpect {
            status { isOk() }
            jsonPath("$.csrfToken") { value(token) }
        }
    }

    @Test
    fun `an unsafe request without the header token is refused, and says which problem it is`() {
        val signedIn = signIn()

        mockMvc
            .post("/console/api/opportunities/$OPPORTUNITY_ID/claim") { session = signedIn }
            .andExpect {
                status { isForbidden() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                // A CSRF refusal and a permission refusal are both 403. Branching on the status
                // alone would hide a real wiring bug behind a plausible "sem permissão".
                jsonPath("$.code") { value("CSRF_VALIDATION_FAILED") }
            }
    }

    @Test
    fun `the check covers every unsafe method, not only POST`() {
        // The server-rendered console only ever posts, so the original check named POST. The JSON
        // door uses DELETE and PUT, and a check that names one verb protects one verb.
        val signedIn = signIn()

        mockMvc
            .delete("/console/api/session") { session = signedIn }
            .andExpect { status { isForbidden() } }
    }

    // --- the board ----------------------------------------------------------

    @Test
    fun `the board arrives grouped, counted and formatted`() {
        val signedIn = signIn()

        mockMvc.get("/console/api/board") { session = signedIn }.andExpect {
            status { isOk() }
            // Grouping happens on the server: the browser never receives a flat list and re-derives
            // the meaning of ten statuses.
            jsonPath("$.counts.go") { value(1) }
            jsonPath("$.counts.wait") { value(1) }
            jsonPath("$.counts.closed") { value(1) }
            jsonPath("$.go[0].window") { value("25/08 19:00–07:00 (+1)") }
            jsonPath("$.go[0].money") { value("R$ 1.200,00") }
            jsonPath("$.go[0].durationLabel") { value("12h") }
            jsonPath("$.go[0].tone") { value("ready") }
            jsonPath("$.wait[0].tone") { value("attention") }
            jsonPath("$.closed[0].tone") { value("closed") }
            jsonPath("$.pulse.tone") { value("live") }
            jsonPath("$.pulse.groupCount") { value(1) }
        }
    }

    @Test
    fun `the board says when it hit the service ceiling instead of implying completeness`() {
        val signedIn = signIn()
        given(opportunityService.list())
            .willReturn(ShiftOpportunityListResponse(listOf(opportunity(OpportunityStatus.ELIGIBLE)), 100, 100))

        mockMvc.get("/console/api/board") { session = signedIn }.andExpect {
            jsonPath("$.atCeiling") { value(true) }
        }
    }

    @Test
    fun `the connection banner reads the stored observation, never a live probe`() {
        val signedIn = signIn()
        given(providerHealth.current()).willReturn(healthy().copy(operational = false, consecutiveFailures = 4))

        mockMvc.get("/console/api/board") { session = signedIn }.andExpect {
            jsonPath("$.pulse.tone") { value("down") }
        }
        // A live probe from a page that polls every fifteen seconds would spend a rate-limited
        // quota on a screen nobody is looking at.
        verify(providerHealth, org.mockito.Mockito.atLeastOnce()).current()
    }

    // --- the action that is the product -------------------------------------

    @Test
    fun `a second claim is a success, not a red card`() {
        val signedIn = signIn()
        given(claimService.claim(OPPORTUNITY_ID, null)).willThrow(
            ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "CONFLICT",
                title = "Opportunity already claimed",
                message = "A claim already exists for this opportunity",
            ),
        )

        mockMvc
            .post("/console/api/opportunities/$OPPORTUNITY_ID/claim") {
                session = signedIn
                header(ConsoleSessionFilter.CSRF_HEADER, csrfTokenOf(signedIn))
            }.andExpect {
                // The message went out. Painting the thing that worked in red is the worst
                // available answer, and this action is the entire product.
                status { isOk() }
                jsonPath("$.alreadyClaimed") { value(true) }
                jsonPath("$.claim.opportunityId") { value(OPPORTUNITY_ID) }
            }
    }

    @Test
    fun `a conflict with no claim to show still fails`() {
        val signedIn = signIn()
        given(claimService.claim(OPPORTUNITY_ID, null)).willThrow(
            ApiProblemException(
                status = HttpStatus.CONFLICT,
                code = "OPPORTUNITY_NOT_CLAIMABLE",
                title = "Not claimable",
                message = "An opportunity that is REJECTED cannot be claimed",
            ),
        )

        mockMvc
            .post("/console/api/opportunities/$OPPORTUNITY_ID/claim") {
                session = signedIn
                header(ConsoleSessionFilter.CSRF_HEADER, csrfTokenOf(signedIn))
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("OPPORTUNITY_NOT_CLAIMABLE") }
            }
    }

    // --- the correction loop ------------------------------------------------

    @Test
    fun `a blank field keeps what was extracted instead of erasing it`() {
        val signedIn = signIn()

        mockMvc
            .post("/console/api/opportunities/$OPPORTUNITY_ID/review") {
                session = signedIn
                header(ConsoleSessionFilter.CSRF_HEADER, csrfTokenOf(signedIn))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {"shiftDate":"2026-08-26","startTime":"","endTime":"  ","location":"",
                     "city":null,"amount":"1.800,00","reviewNote":"","version":3}
                    """.trimIndent()
            }.andExpect { status { isOk() } }

        // The service writes `request.location ?: current.location`, and "" is not null. Sending
        // the empty string through would erase the location rather than leave it alone.
        verify(opportunityService).review(
            OPPORTUNITY_ID,
            ReviewOpportunityRequest(
                shiftDate = "2026-08-26",
                startTime = null,
                endTime = null,
                location = null,
                city = null,
                amount = BigDecimal("1800.00"),
                reviewNote = null,
                version = 3,
            ),
        )
    }

    @Test
    fun `an amount is read the way a Brazilian writes it`() {
        val signedIn = signIn()

        mockMvc
            .post("/console/api/opportunities/$OPPORTUNITY_ID/review") {
                session = signedIn
                header(ConsoleSessionFilter.CSRF_HEADER, csrfTokenOf(signedIn))
                contentType = MediaType.APPLICATION_JSON
                content = """{"amount":"1800","version":3}"""
            }.andExpect { status { isOk() } }

        verify(opportunityService).review(OPPORTUNITY_ID, ReviewOpportunityRequest(amount = BigDecimal("1800"), version = 3))
    }

    @Test
    fun `an amount that is not a number is a request problem, not a silent zero`() {
        val signedIn = signIn()

        mockMvc
            .post("/console/api/opportunities/$OPPORTUNITY_ID/review") {
                session = signedIn
                header(ConsoleSessionFilter.CSRF_HEADER, csrfTokenOf(signedIn))
                contentType = MediaType.APPLICATION_JSON
                content = """{"amount":"mil e oitocentos","version":3}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_REQUEST") }
            }
    }

    // --- the corpus ---------------------------------------------------------

    @Test
    fun `a message written by a stranger arrives whole and as data`() {
        val signedIn = signIn()

        val body =
            mockMvc
                .get("/console/api/messages") { session = signedIn }
                .andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString

        val text =
            objectMapper
                .readTree(body)
                .get("messages")
                .get(0)
                .get("text")
                .asString()
        // Whole: this is the corpus the extraction work is measured against, and a truncated
        // message is a corrupted sample. As data: the escaping that keeps it safe is the client's
        // interpolation, which is why innerHTML is a lint error in the app that renders this.
        assertEquals(HOSTILE, text)
    }

    // --- the agenda trap ----------------------------------------------------

    @Test
    fun `a claimed shift is not offered a delete button that would 404`() {
        val signedIn = signIn()

        mockMvc.get("/console/api/agenda") { session = signedIn }.andExpect {
            status { isOk() }
            // reference is the entry id for a MANUAL row and the OPPORTUNITY id for a CLAIM row.
            // The delete endpoint has never heard of the second, so offering the button would
            // produce a puzzling 404 for a row she can see.
            jsonPath("$.commitments[0].removable") { value(true) }
            jsonPath("$.commitments[1].removable") { value(false) }
        }
    }

    // --- fixtures -----------------------------------------------------------

    private fun signIn(): MockHttpSession {
        val result =
            mockMvc
                .post("/console/api/session") {
                    session = MockHttpSession()
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"token":"test-admin-token"}"""
                }.andExpect { status { isOk() } }
                .andReturn()
        // Authentication replaces the session id, so the useful handle is the one sign-in left.
        return result.request.session as MockHttpSession
    }

    private fun csrfTokenOf(session: MockHttpSession): String = session.getAttribute(ConsoleSessionFilter.CSRF_TOKEN) as String

    private fun opportunity(
        status: OpportunityStatus,
        id: String = OPPORTUNITY_ID,
    ): ShiftOpportunityResponse =
        ShiftOpportunityResponse(
            id = id,
            sourceMessageId = MESSAGE_ID,
            groupId = UUID.randomUUID().toString(),
            status = status,
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
            resolutionReason = if (status == OpportunityStatus.REJECTED) "RULE_REJECTED" else null,
            reviewNote = null,
            version = 3,
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

    private fun manualCommitment(): CommitmentResponse =
        CommitmentResponse(
            source = CommitmentSource.MANUAL,
            reference = UUID.randomUUID().toString(),
            label = "Santa Casa",
            shiftDate = LocalDate.of(2026, 9, 3),
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(13, 0),
            endsNextDay = false,
        )

    private fun claimCommitment(): CommitmentResponse =
        CommitmentResponse(
            source = CommitmentSource.CLAIM,
            reference = OPPORTUNITY_ID,
            label = null,
            shiftDate = LocalDate.of(2026, 9, 5),
            startTime = LocalTime.of(19, 0),
            endTime = LocalTime.of(7, 0),
            endsNextDay = true,
        )

    private fun group(): AllowedGroupResponse =
        AllowedGroupResponse(
            id = UUID.randomUUID().toString(),
            providerChatId = "120363000000000000@g.us",
            displayName = "Plantoes",
            enabled = true,
            autoClaimEnabled = false,
            claimMessage = null,
            version = 0,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun healthy(): ProviderHealthObservation =
        ProviderHealthObservation(
            state = "authorized",
            operational = true,
            observedAt = NOW,
            consecutiveFailures = 0,
            lastError = null,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-24T21:00:00Z")
        val OPPORTUNITY_ID: String = UUID.randomUUID().toString()
        val WAITING_ID: String = UUID.randomUUID().toString()
        val CLOSED_ID: String = UUID.randomUUID().toString()
        val MESSAGE_ID: String = UUID.randomUUID().toString()

        /** What a hostile participant would post if the app ever rendered raw HTML. */
        const val HOSTILE = "Plantao amanha <script>alert('x')</script>"
    }
}
