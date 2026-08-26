package br.com.shiftcatcher.console

import br.com.shiftcatcher.availability.AvailabilityService
import br.com.shiftcatcher.availability.CreateAvailabilityRequest
import br.com.shiftcatcher.claim.ClaimMessageRequest
import br.com.shiftcatcher.claim.ClaimMessageService
import br.com.shiftcatcher.claim.ClaimService
import br.com.shiftcatcher.claim.ClaimStatus
import br.com.shiftcatcher.claim.RetractClaimRequest
import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.foundation.http.ApiProblemException
import br.com.shiftcatcher.group.AllowedGroupService
import br.com.shiftcatcher.messaging.IncomingMessageResponse
import br.com.shiftcatcher.messaging.IngestionService
import br.com.shiftcatcher.reliability.ProviderHealthGate
import br.com.shiftcatcher.rules.OpportunityEvaluationService
import br.com.shiftcatcher.shift.IgnoreOpportunityRequest
import br.com.shiftcatcher.shift.OpportunityStatus
import br.com.shiftcatcher.shift.ReviewOpportunityRequest
import br.com.shiftcatcher.shift.ShiftOpportunityResponse
import br.com.shiftcatcher.shift.ShiftOpportunityService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * The JSON front door the operator app talks to.
 *
 * It is composition over the same in-process services the server-rendered console and the admin API
 * already call. It adds no domain, and it deliberately sits outside `/api/v1`: `AdminBearerFilter`
 * guards that whole prefix with a single static bearer token, and `AUTODEC-0009` decision 5 keeps
 * that token out of a browser. What the browser gets instead is a session cookie that is worthless
 * anywhere else.
 *
 * Three responsibilities live here rather than in the client, because in each case putting them in
 * the client would mean two implementations of one rule:
 *
 *   - grouping. `Pode pegar`, `Precisa de você` and `Encerradas` are derived from the status here.
 *     A browser that receives a flat list and sorts it owns a second copy of the state machine.
 *   - formatting. Windows, money and date eyebrows are built in her timezone by [ConsoleFormatter].
 *   - the claim conflict. A second claim on the same opportunity is a 409 from the service and a
 *     success from where she is standing; translating it is this layer's job.
 */
@RestController
@RequestMapping("/console/api")
class ConsoleApiController(
    private val opportunityService: ShiftOpportunityService,
    private val evaluationService: OpportunityEvaluationService,
    private val claimService: ClaimService,
    private val claimMessageService: ClaimMessageService,
    private val availabilityService: AvailabilityService,
    private val ingestionService: IngestionService,
    private val groupService: AllowedGroupService,
    private val providerHealth: ProviderHealthGate,
    private val properties: ShiftCatcherProperties,
) {
    private val clock: Clock = Clock.systemUTC()
    private val formatter = ConsoleFormatter(properties.detection.timezone, clock)

    // --- session ------------------------------------------------------------

    /**
     * Exchanges the admin token for a session.
     *
     * This is the one operation the session filter lets through unauthenticated, and the response
     * carries the CSRF token because the cookie that arrives with it is HttpOnly and the app will
     * never be able to read it.
     */
    @PostMapping("/session")
    fun signIn(
        @RequestBody body: ConsoleSignInRequest,
        request: HttpServletRequest,
    ): ConsoleSessionResponse {
        val expected = properties.security.adminApiToken
        val presented = body.token.orNull()
        if (expected.isBlank() || presented == null || !ConsoleSessionFilter.constantTimeEquals(expected, presented)) {
            logger.warn("Rejected a console sign-in with an incorrect token")
            // One message for a wrong token and for a console with no token configured. Telling the
            // two apart tells an attacker whether there is anything here worth guessing at.
            throw ApiProblemException(
                status = HttpStatus.UNAUTHORIZED,
                code = "INVALID_REQUEST",
                title = "Sign-in refused",
                message = "The token was not accepted",
            )
        }
        // A new session id after authenticating: whatever id a third party may have planted in the
        // browser beforehand is not the one that ends up carrying the privilege.
        request.getSession(false)?.invalidate()
        val session = request.getSession(true)
        session.setAttribute(ConsoleSessionFilter.AUTHENTICATED, true)
        val csrfToken = ConsoleSessionFilter.newCsrfToken()
        session.setAttribute(ConsoleSessionFilter.CSRF_TOKEN, csrfToken)
        return ConsoleSessionResponse(true, csrfToken, session.maxInactiveInterval.toLong())
    }

    /**
     * Recovers the session after a page reload.
     *
     * Without this the operator stays signed in — the cookie is good for hours — while the app
     * loses the CSRF token it was handed at sign-in, so every action starts failing with 403 and
     * her only remedy is to sign out and in again after every refresh.
     */
    @GetMapping("/session")
    fun session(session: HttpSession): ConsoleSessionResponse {
        val csrfToken =
            session.getAttribute(ConsoleSessionFilter.CSRF_TOKEN) as? String
                ?: ConsoleSessionFilter.newCsrfToken().also {
                    session.setAttribute(ConsoleSessionFilter.CSRF_TOKEN, it)
                }
        return ConsoleSessionResponse(true, csrfToken, session.maxInactiveInterval.toLong())
    }

    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun signOut(session: HttpSession) {
        session.invalidate()
    }

    // --- board --------------------------------------------------------------

    /** The whole first screen in one call: three groups, the counts, and the connection banner. */
    @GetMapping("/board")
    fun board(): ConsoleBoardResponse {
        val listing = opportunityService.list()
        val messages = ingestionService.list()
        val sources = messages.messages.associateBy { it.id }

        val go =
            listing.opportunities
                .filter { it.status == OpportunityStatus.ELIGIBLE }
                // Soonest first: the one she can lose by waiting is the one that starts next.
                .sortedWith(compareBy(nullsLast()) { it.shiftDate })
                .map { it.toCard(formatter, sources[it.sourceMessageId]) }
        val wait =
            listing.opportunities
                .filter { it.status.isOpenForAnalysis() }
                .map { it.toCard(formatter, sources[it.sourceMessageId]) }
        val closed =
            listing.opportunities
                .filter { it.status != OpportunityStatus.ELIGIBLE && !it.status.isOpenForAnalysis() }
                .map { it.toClosedRow(formatter) }

        return ConsoleBoardResponse(
            serverTime = formatter.moment(clock.instant()),
            pulse = pulse(messages.messages),
            counts = ConsoleCounts(go = go.size, wait = wait.size, closed = closed.size),
            go = go,
            wait = wait,
            closed = closed,
            limit = listing.limit,
            // The three groups share one hundred-row ceiling with no cursor behind it, so a busy
            // day pushes live offers out to make room for closed ones. The screen has to say so.
            atCeiling = listing.count >= listing.limit,
        )
    }

    // --- one opportunity ----------------------------------------------------

    @GetMapping("/opportunities/{opportunityId}")
    fun opportunity(
        @PathVariable opportunityId: String,
    ): ConsoleOpportunityDetail = detailOf(opportunityId)

    /**
     * The action that is the product.
     *
     * A second call for the same opportunity is answered by the service with 409, because a claim
     * already exists. From where she is standing that is not a failure — the message went out — so
     * it comes back as the existing claim with a flag, and the screen says "já pego" instead of
     * painting the thing that worked in red.
     */
    @PostMapping("/opportunities/{opportunityId}/claim")
    fun claim(
        @PathVariable opportunityId: String,
    ): ConsoleClaimAck =
        try {
            ConsoleClaimAck(claim = claimService.claim(opportunityId, null).toRow(formatter), alreadyClaimed = false)
        } catch (conflict: ApiProblemException) {
            val existing =
                if (conflict.status == HttpStatus.CONFLICT && conflict.code == "CONFLICT") {
                    // There is no service method that finds a claim by opportunity, so the list is
                    // the only public way in. It is capped at a hundred rows, which is why a miss
                    // rethrows rather than inventing a success.
                    //
                    // RETRACTED and FAILED are excluded on purpose. ClaimService guards on the
                    // existence of a row and not on its state, so a claim she already took back —
                    // or one the provider refused — still produces this 409. Reporting that as
                    // "já pego" tells her she holds a shift that nobody in the group was ever told
                    // she wanted. A dead row falls through to the honest conflict below.
                    claimService.list().claims.firstOrNull {
                        it.opportunityId == opportunityId &&
                            it.status != ClaimStatus.RETRACTED &&
                            it.status != ClaimStatus.FAILED
                    }
                } else {
                    null
                }
            existing?.let { ConsoleClaimAck(claim = it.toRow(formatter), alreadyClaimed = true) }
                ?: throw deadClaimOr(conflict, opportunityId)
        }

    /**
     * The manual reading.
     *
     * Every field is optional and a blank one keeps what was extracted. That is not a nicety: the
     * service writes `request.location ?: current.location`, and an empty string is not null, so
     * sending "" would erase the location instead of leaving it alone. The conversion from blank to
     * absent happens here, once, rather than in each form.
     *
     * There is no field for "crosses into the next day" because there is no such field to send: the
     * service derives it from the two times.
     */
    @PostMapping("/opportunities/{opportunityId}/review")
    fun review(
        @PathVariable opportunityId: String,
        @RequestBody body: ConsoleReviewRequest,
    ): ConsoleOpportunityDetail {
        opportunityService.review(
            opportunityId,
            ReviewOpportunityRequest(
                shiftDate = body.shiftDate.orNull(),
                startTime = body.startTime.orNull(),
                endTime = body.endTime.orNull(),
                location = body.location.orNull(),
                city = body.city.orNull(),
                amount = amountOf(body.amount),
                reviewNote = body.reviewNote.orNull(),
                version = body.version,
            ),
        )
        return detailOf(opportunityId)
    }

    /**
     * Runs the rules again.
     *
     * This can reject the offer, including for the reason that no rule set is active. That is a
     * refusal by policy, not a fault, and the response carries the repainted card so the screen
     * never has to guess which of the two happened.
     */
    @PostMapping("/opportunities/{opportunityId}/reevaluate")
    fun reevaluate(
        @PathVariable opportunityId: String,
    ): ConsoleEvaluationResponse {
        val evaluation = evaluationService.reevaluate(opportunityId)
        return ConsoleEvaluationResponse(
            opportunityId = evaluation.opportunityId,
            status = evaluation.status.name,
            result = evaluation.result.name,
            reasons = evaluation.reasons,
            evaluatedAt = formatter.moment(evaluation.evaluatedAt),
            detail = detailOf(opportunityId),
        )
    }

    @PostMapping("/opportunities/{opportunityId}/ignore")
    fun ignore(
        @PathVariable opportunityId: String,
        @RequestBody body: ConsoleIgnoreRequest,
    ): ConsoleOpportunityDetail {
        opportunityService.ignore(
            opportunityId,
            IgnoreOpportunityRequest(reviewNote = body.reviewNote.orNull(), version = body.version),
        )
        return detailOf(opportunityId)
    }

    /**
     * Says which kind of "already claimed" this is, when the claim is not a live one.
     *
     * Rethrowing the service's CONFLICT verbatim was a second false sentence in the same place. The
     * app maps CONFLICT to "alguém chegou primeiro" and marks it permanently unretryable — but
     * nobody got there first: her own send failed, the shift is unclaimed, and
     * `POST /console/api/claims/{id}/retry` would re-arm the very send that failed. She stops
     * looking for a shift that is still there.
     */
    private fun deadClaimOr(
        conflict: ApiProblemException,
        opportunityId: String,
    ): ApiProblemException {
        if (conflict.status != HttpStatus.CONFLICT || conflict.code != "CONFLICT") return conflict
        val dead = claimService.list().claims.firstOrNull { it.opportunityId == opportunityId } ?: return conflict
        return when (dead.status) {
            ClaimStatus.FAILED -> {
                ApiProblemException(
                    status = HttpStatus.CONFLICT,
                    code = "CLAIM_SEND_FAILED",
                    title = "The claim was created but never sent",
                    message = "Retry the existing claim rather than creating a second one",
                )
            }

            ClaimStatus.RETRACTED -> {
                ApiProblemException(
                    status = HttpStatus.CONFLICT,
                    code = "CLAIM_RETRACTED",
                    title = "The claim was taken back",
                    message = "This opportunity was claimed and the claim was retracted",
                )
            }

            else -> {
                conflict
            }
        }
    }

    // --- claims -------------------------------------------------------------

    @GetMapping("/claims")
    fun claims(): ConsoleClaimListResponse {
        val listing = claimService.list()
        return ConsoleClaimListResponse(
            claims = listing.claims.map { it.toRow(formatter) },
            count = listing.count,
            limit = listing.limit,
            atCeiling = listing.count >= listing.limit,
        )
    }

    @PostMapping("/claims/{claimId}/retry")
    fun retry(
        @PathVariable claimId: String,
    ): ConsoleClaimRow = claimService.retry(claimId).toRow(formatter)

    /**
     * Takes the message back.
     *
     * The response is the claim as it now stands, and it can come back still CLAIMED: WhatsApp can
     * refuse a deletion, and when it does the message is still sitting in the group. The screen must
     * read the returned status rather than striking the row out optimistically.
     */
    @PostMapping("/claims/{claimId}/retract")
    fun retract(
        @PathVariable claimId: String,
        @RequestBody body: ConsoleRetractRequest,
    ): ConsoleClaimRow = claimService.retract(claimId, RetractClaimRequest(reason = body.reason.orNull())).toRow(formatter)

    // --- messages -----------------------------------------------------------

    /** The corpus. Text arrives whole and is never truncated here; it is the evidence. */
    @GetMapping("/messages")
    fun messages(): ConsoleMessageListResponse {
        val listing = ingestionService.list()
        return ConsoleMessageListResponse(
            messages = listing.messages.map { it.toRow(formatter) },
            count = listing.count,
            limit = listing.limit,
            atCeiling = listing.count >= listing.limit,
        )
    }

    // --- agenda -------------------------------------------------------------

    @GetMapping("/agenda")
    fun agenda(
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): ConsoleAgendaResponse {
        val listing = availabilityService.list(from, to)
        return ConsoleAgendaResponse(
            from = listing.from,
            to = listing.to,
            commitments = listing.commitments.map { it.toRow(formatter) },
            count = listing.count,
        )
    }

    @PostMapping("/agenda")
    @ResponseStatus(HttpStatus.CREATED)
    fun addCommitment(
        @RequestBody body: ConsoleCommitmentRequest,
    ): ConsoleAgendaResponse {
        val shiftDate =
            body.shiftDate.orNull()?.let(LocalDate::parse)
                ?: throw IllegalArgumentException("shiftDate is required")
        val start = body.startTime.orNull()?.let(LocalTime::parse)
        val end = body.endTime.orNull()?.let(LocalTime::parse)
        availabilityService.create(
            CreateAvailabilityRequest(
                shiftDate = shiftDate,
                startTime = start,
                endTime = end,
                // Derived, not asked: the same rule the review path uses, so the two agree.
                endsNextDay = start != null && end != null && !end.isAfter(start),
                label = body.label.orNull(),
                note = body.note.orNull(),
            ),
        )
        return agenda(null, null)
    }

    /**
     * Removes a commitment she typed in.
     *
     * Only a MANUAL entry has a reference this endpoint can use. A CLAIM row's reference is the
     * OPPORTUNITY id, which this table has never heard of, so the board marks those rows as not
     * removable rather than offering a button that 404s.
     */
    @DeleteMapping("/agenda/{reference}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteCommitment(
        @PathVariable reference: String,
    ) {
        availabilityService.delete(reference)
    }

    // --- settings -----------------------------------------------------------

    @GetMapping("/settings/claim-message")
    fun claimMessage(): ConsoleClaimMessageResponse = claimMessageService.current().toApi()

    @PutMapping("/settings/claim-message")
    fun updateClaimMessage(
        @RequestBody body: ConsoleClaimMessageRequest,
    ): ConsoleClaimMessageResponse =
        claimMessageService
            .update(ClaimMessageRequest(message = body.message, version = body.version))
            .toApi()

    // --- plumbing -----------------------------------------------------------

    private fun detailOf(opportunityId: String): ConsoleOpportunityDetail {
        val opportunity = opportunityService.detail(opportunityId)
        return opportunity.toDetail(formatter, sourceOf(opportunity))
    }

    /**
     * The original wording behind one opportunity.
     *
     * `detail` rather than scanning `list`, which is what the server-rendered console does: the list
     * is capped at a hundred rows, so an older opportunity opened from a link would lose the quoted
     * message that is the thing she actually recognises.
     */
    private fun sourceOf(opportunity: ShiftOpportunityResponse): IncomingMessageResponse? =
        runCatching { ingestionService.detail(opportunity.sourceMessageId) }.getOrNull()

    private fun pulse(messages: List<IncomingMessageResponse>): ConsolePulse {
        val observation = providerHealth.current()
        val fresh = observation != null && providerHealth.isFresh(observation)
        val tone =
            when {
                observation == null -> "down"
                !observation.operational && observation.consecutiveFailures >= DOWN_AFTER -> "down"
                !observation.operational -> "degraded"
                !fresh -> "degraded"
                else -> "live"
            }
        return ConsolePulse(
            tone = tone,
            label =
                when (tone) {
                    "live" -> "Escutando os grupos"
                    "degraded" -> "Conexão instável"
                    else -> "Sem conexão com o WhatsApp"
                },
            groupCount = groupService.list().groups.count { it.enabled },
            lastMessageAt = formatter.moment(messages.maxByOrNull { it.receivedAt }?.receivedAt),
            reason = observation?.lastError,
            stale = !fresh,
        )
    }

    /**
     * Reads the amount the way she types it.
     *
     * One line, because the rule lives in [BrazilianAmount] and is shared with the server-rendered
     * console. Three private copies of it is how the same string came to mean three different
     * numbers in one product.
     */
    private fun amountOf(raw: String?): BigDecimal? = BrazilianAmount.parse(raw)

    private fun br.com.shiftcatcher.claim.ClaimMessageResponse.toApi(): ConsoleClaimMessageResponse =
        ConsoleClaimMessageResponse(
            message = message,
            version = version,
            updatedAt = formatter.moment(updatedAt),
        )

    private fun String?.orNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        /** Two failures is a blip; three in a row is an outage worth telling her about. */
        const val DOWN_AFTER = 3
        val logger = LoggerFactory.getLogger(ConsoleApiController::class.java)
    }
}
