package br.com.shiftcatcher.console

import br.com.shiftcatcher.availability.AvailabilityService
import br.com.shiftcatcher.availability.CreateAvailabilityRequest
import br.com.shiftcatcher.claim.ClaimMessageRequest
import br.com.shiftcatcher.claim.ClaimMessageService
import br.com.shiftcatcher.claim.ClaimService
import br.com.shiftcatcher.claim.RetractClaimRequest
import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.foundation.http.ApiProblemException
import br.com.shiftcatcher.messaging.IngestionService
import br.com.shiftcatcher.rules.OpportunityEvaluationService
import br.com.shiftcatcher.shift.IgnoreOpportunityRequest
import br.com.shiftcatcher.shift.ReviewOpportunityRequest
import br.com.shiftcatcher.shift.ShiftOpportunityService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDate
import java.time.LocalTime

/**
 * The operator console of `12-MVP/MVP-Scope.md` item 3.
 *
 * It renders on the server and calls the same services the JSON API calls, in-process. It adds no
 * `/api/v1` operation, so the frozen 42-operation contract is untouched: this is a second front
 * door onto the existing product, not new product.
 *
 * Every state-changing action is a POST that redirects (`POST/redirect/GET`), so a reload never
 * re-claims a shift.
 */
@Controller
@RequestMapping("/console")
class ConsoleController(
    private val opportunityService: ShiftOpportunityService,
    private val evaluationService: OpportunityEvaluationService,
    private val claimService: ClaimService,
    private val claimMessageService: ClaimMessageService,
    private val availabilityService: AvailabilityService,
    private val ingestionService: IngestionService,
    private val properties: ShiftCatcherProperties,
) {
    private val formatter = ConsoleFormatter(properties.detection.timezone)

    // --- session ------------------------------------------------------------

    @GetMapping("/login")
    fun loginForm(
        session: HttpSession,
        model: Model,
    ): String {
        if (session.getAttribute(ConsoleSessionFilter.AUTHENTICATED) == true) {
            return "redirect:/console"
        }
        model.addAttribute("failed", false)
        return "console/login"
    }

    @PostMapping("/login")
    fun login(
        @RequestParam token: String,
        request: HttpServletRequest,
        model: Model,
    ): String {
        val expected = properties.security.adminApiToken
        if (expected.isBlank() || !ConsoleSessionFilter.constantTimeEquals(expected, token)) {
            logger.warn("Rejected a console sign-in with an incorrect token")
            model.addAttribute("failed", true)
            return "console/login"
        }
        // A new session id after authenticating: whatever id a third party may have planted in the
        // browser beforehand is not the one that ends up carrying the privilege.
        request.getSession(false)?.invalidate()
        val session = request.getSession(true)
        session.setAttribute(ConsoleSessionFilter.AUTHENTICATED, true)
        session.setAttribute(ConsoleSessionFilter.CSRF_TOKEN, ConsoleSessionFilter.newCsrfToken())
        return "redirect:/console"
    }

    @PostMapping("/logout")
    fun logout(session: HttpSession): String {
        session.invalidate()
        return "redirect:/console/login"
    }

    // --- opportunities ------------------------------------------------------

    // Both spellings: Spring stopped matching trailing slashes in 6.0, and a 404 for typing the
    // address with one would be a baffling way to meet the product.
    @GetMapping("", "/")
    fun opportunities(
        session: HttpSession,
        model: Model,
    ): String {
        val texts = sourceTexts()
        val opportunities =
            opportunityService.list().opportunities.map {
                it.toView(
                    formatter = formatter,
                    sourceText = texts[it.sourceMessageId]?.text ?: "",
                    senderName = texts[it.sourceMessageId]?.sender ?: "",
                )
            }
        model.addAttribute("opportunities", opportunities)
        model.addAttribute("waiting", opportunities.count { it.status == "REVIEW_REQUIRED" })
        model.addAttribute("ready", opportunities.count { it.claimable })
        return page(session, model, "opportunities", "console/opportunities")
    }

    @GetMapping("/opportunities/{opportunityId}")
    fun opportunity(
        @PathVariable opportunityId: String,
        session: HttpSession,
        model: Model,
    ): String {
        val opportunity = opportunityService.detail(opportunityId)
        val source = sourceTexts()[opportunity.sourceMessageId]
        model.addAttribute(
            "opportunity",
            opportunity.toView(formatter, source?.text ?: "", source?.sender ?: ""),
        )
        model.addAttribute("raw", opportunity)
        return page(session, model, "opportunities", "console/opportunity")
    }

    @PostMapping("/opportunities/{opportunityId}/claim")
    fun claim(
        @PathVariable opportunityId: String,
        attributes: RedirectAttributes,
    ): String =
        act(attributes, "/console") {
            val claim = claimService.claim(opportunityId, null)
            "Enviando \"${claim.message}\" citando a oferta."
        }

    @PostMapping("/opportunities/{opportunityId}/ignore")
    fun ignore(
        @PathVariable opportunityId: String,
        @RequestParam version: Int,
        attributes: RedirectAttributes,
    ): String =
        act(attributes, "/console") {
            opportunityService.ignore(opportunityId, IgnoreOpportunityRequest(version = version))
            "Oferta descartada."
        }

    @PostMapping("/opportunities/{opportunityId}/reevaluate")
    fun reevaluate(
        @PathVariable opportunityId: String,
        attributes: RedirectAttributes,
    ): String =
        act(attributes, "/console/opportunities/$opportunityId") {
            val evaluation = evaluationService.reevaluate(opportunityId)
            val reasons = evaluation.reasons.joinToString(", ").ifBlank { "sem ressalvas" }
            "Reavaliada: ${evaluation.result} ($reasons)."
        }

    /**
     * The manual reading of `EP-020`. Everything is optional: a blank field keeps what was extracted
     * rather than erasing it, so correcting only the hour does not cost the date.
     */
    @PostMapping("/opportunities/{opportunityId}/review")
    fun review(
        @PathVariable opportunityId: String,
        @RequestParam version: Int,
        @RequestParam(required = false) shiftDate: String?,
        @RequestParam(required = false) startTime: String?,
        @RequestParam(required = false) endTime: String?,
        @RequestParam(required = false) location: String?,
        @RequestParam(required = false) city: String?,
        @RequestParam(required = false) amount: String?,
        @RequestParam(required = false) reviewNote: String?,
        attributes: RedirectAttributes,
    ): String =
        act(attributes, "/console/opportunities/$opportunityId") {
            opportunityService.review(
                opportunityId,
                ReviewOpportunityRequest(
                    shiftDate = shiftDate.orNull(),
                    startTime = startTime.orNull(),
                    endTime = endTime.orNull(),
                    location = location.orNull(),
                    city = city.orNull(),
                    amount = amount.orNull()?.replace(',', '.')?.toBigDecimal(),
                    reviewNote = reviewNote.orNull(),
                    version = version,
                ),
            )
            "Leitura corrigida. As regras são aplicadas na sequência."
        }

    // --- claims -------------------------------------------------------------

    @GetMapping("/claims")
    fun claims(
        session: HttpSession,
        model: Model,
    ): String {
        model.addAttribute("claims", claimService.list().claims.map { it.toView(formatter) })
        return page(session, model, "claims", "console/claims")
    }

    @PostMapping("/claims/{claimId}/retry")
    fun retry(
        @PathVariable claimId: String,
        attributes: RedirectAttributes,
    ): String =
        act(attributes, "/console/claims") {
            claimService.retry(claimId)
            "Reenvio rearmado."
        }

    @PostMapping("/claims/{claimId}/retract")
    fun retract(
        @PathVariable claimId: String,
        @RequestParam(required = false) reason: String?,
        attributes: RedirectAttributes,
    ): String =
        act(attributes, "/console/claims") {
            claimService.retract(claimId, RetractClaimRequest(reason = reason.orNull()))
            "Mensagem apagada no grupo. O WhatsApp deixa a marca de apagada."
        }

    // --- messages -----------------------------------------------------------

    @GetMapping("/messages")
    fun messages(
        session: HttpSession,
        model: Model,
    ): String {
        model.addAttribute("messages", ingestionService.list().messages.map { it.toView(formatter) })
        return page(session, model, "messages", "console/messages")
    }

    // --- agenda -------------------------------------------------------------

    @GetMapping("/agenda")
    fun agenda(
        session: HttpSession,
        model: Model,
    ): String {
        val listing = availabilityService.list(null, null)
        model.addAttribute("commitments", listing.commitments.map { it.toView(formatter) })
        model.addAttribute("from", listing.from)
        model.addAttribute("to", listing.to)
        return page(session, model, "agenda", "console/agenda")
    }

    @PostMapping("/agenda")
    fun addCommitment(
        @RequestParam shiftDate: String,
        @RequestParam(required = false) startTime: String?,
        @RequestParam(required = false) endTime: String?,
        @RequestParam(required = false) endsNextDay: Boolean?,
        @RequestParam(required = false) label: String?,
        attributes: RedirectAttributes,
    ): String =
        act(attributes, "/console/agenda") {
            availabilityService.create(
                CreateAvailabilityRequest(
                    shiftDate = LocalDate.parse(shiftDate),
                    startTime = startTime.orNull()?.let(LocalTime::parse),
                    endTime = endTime.orNull()?.let(LocalTime::parse),
                    endsNextDay = endsNextDay ?: false,
                    label = label.orNull(),
                ),
            )
            "Compromisso registrado."
        }

    @PostMapping("/agenda/{entryId}/delete")
    fun deleteCommitment(
        @PathVariable entryId: String,
        attributes: RedirectAttributes,
    ): String =
        act(attributes, "/console/agenda") {
            availabilityService.delete(entryId)
            "Compromisso removido."
        }

    // --- settings -----------------------------------------------------------

    @GetMapping("/settings")
    fun settings(
        session: HttpSession,
        model: Model,
    ): String {
        model.addAttribute("claimMessage", claimMessageService.current())
        return page(session, model, "settings", "console/settings")
    }

    @PostMapping("/settings/claim-message")
    fun updateClaimMessage(
        @RequestParam message: String,
        @RequestParam version: Int,
        attributes: RedirectAttributes,
    ): String =
        act(attributes, "/console/settings") {
            val updated = claimMessageService.update(ClaimMessageRequest(message = message, version = version))
            "A partir de agora a resposta é \"${updated.message}\"."
        }

    // --- plumbing -----------------------------------------------------------

    /**
     * Runs an action and turns its failure into something a person can read.
     *
     * The shared `@RestControllerAdvice` would answer a browser form with a JSON problem document.
     * A refusal here is usually ordinary — the shift was taken while she was looking at it — so it
     * belongs on the page as a sentence, not as a stack of JSON.
     */
    private fun act(
        attributes: RedirectAttributes,
        redirectTo: String,
        action: () -> String,
    ): String {
        try {
            attributes.addFlashAttribute("notice", action())
        } catch (failure: ApiProblemException) {
            attributes.addFlashAttribute("problem", failure.message)
        } catch (failure: IllegalArgumentException) {
            attributes.addFlashAttribute("problem", failure.message ?: "Requisição inválida")
        }
        return "redirect:$redirectTo"
    }

    private fun page(
        session: HttpSession,
        model: Model,
        active: String,
        view: String,
    ): String {
        model.addAttribute("active", active)
        model.addAttribute(ConsoleSessionFilter.CSRF_FIELD, session.getAttribute(ConsoleSessionFilter.CSRF_TOKEN))
        return view
    }

    private data class Source(
        val text: String,
        val sender: String,
    )

    /**
     * The original wording behind each opportunity, which is what she actually recognises. One list
     * call rather than one query per row: with a single operator both are cheap, and this one stays
     * cheap if the list grows.
     */
    private fun sourceTexts(): Map<String, Source> =
        ingestionService
            .list()
            .messages
            .associate { it.id to Source(it.text, it.senderName ?: it.senderId) }

    private fun String?.orNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val logger = LoggerFactory.getLogger(ConsoleController::class.java)
    }
}
