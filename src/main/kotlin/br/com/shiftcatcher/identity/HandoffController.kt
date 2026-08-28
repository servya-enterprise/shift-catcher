package br.com.shiftcatcher.identity

import br.com.shiftcatcher.console.ConsoleSessionFilter
import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.view.RedirectView
import java.time.Clock

/**
 * The one door Clara Care can open.
 *
 * A staff member follows the Plantões link, arrives here with an assertion in the query string, and
 * leaves with this product's own session cookie on this product's own origin — which is the whole
 * of what AUTODEC-0012 promised. One login, from the person's side. Two sessions, from the
 * browser's.
 *
 * IT IS A NAVIGATION, NOT A FETCH, and that decides three things.
 *
 * The assertion never reaches JavaScript: the browser sends it, this endpoint consumes it, and the
 * redirect that follows carries no trace of it. Had the single-page app read it out of the URL, a
 * credential would have been a string in the same document that renders WhatsApp messages written
 * by strangers — the exact thing `ConsoleSessionFilter` was built to avoid.
 *
 * The redirect is what removes it from the address bar, and with it from the history entry the next
 * page would otherwise inherit as a referrer.
 *
 * And a failure has to be a page, because a person is looking at it. A problem+json body would be
 * shown to somebody who followed a menu item, as text, in a browser window.
 */
@Controller
class HandoffController(
    private val operators: OperatorRepository,
    private val properties: ShiftCatcherProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val verifier by lazy {
        HandoffVerifier(
            publicKeyBase64 = properties.handoff.publicKey,
            expectedIssuer = properties.handoff.issuer,
            expectedAudience = properties.handoff.audience,
            clockSkew = properties.handoff.clockSkew,
        )
    }

    @GetMapping(HANDOFF_PATH)
    fun redeem(
        @RequestParam(name = "h", required = false) token: String?,
        request: HttpServletRequest,
    ): RedirectView {
        val now = clock.instant()
        // Cheap, and it keeps the table from being a log. Doing it on the way in rather than on a
        // schedule means the sweep runs exactly as often as the door is used.
        operators.forgetExpired(now)

        if (token.isNullOrBlank()) return refuse(null)

        val result = verifier.verify(token, now)
        if (result is HandoffResult.Refused) {
            // The reason is logged and never shown. "Assinatura inválida" tells somebody probing
            // this endpoint which of their guesses was closer; the person who legitimately waited
            // too long needs to know only that it expired, which is what the page says.
            securityLogger.warn("Refused a Clara Care handoff: {}", result.failure.reason)
            return refuse(result.failure)
        }

        val assertion = (result as HandoffResult.Accepted).assertion
        val operator = operators.findBySubject(assertion.subject)
        if (operator == null || !operator.active) {
            // A valid assertion for somebody this product has never heard of. Clara Care vouched
            // for who they are, which is not the same as this product agreeing they belong here,
            // and creating the operator now would make the first visit its own authorisation.
            securityLogger.warn("Refused a valid handoff for an unknown or inactive operator")
            return refuse(null)
        }

        // Last, and inside the transaction that owns the primary key: everything above is a read,
        // and spending the assertion before knowing it would be accepted would burn a link on a
        // refusal the person is about to be asked to retry.
        if (!operators.redeem(assertion.id, assertion.expiresAt)) {
            securityLogger.warn("Refused a Clara Care handoff that had already been redeemed")
            return refuse(HandoffFailure.AlreadyUsed)
        }

        // A fresh session id for a fresh sign-in. Reusing the one the browser arrived with is how a
        // session fixated before login survives it.
        request.getSession(false)?.invalidate()
        val session = request.getSession(true)
        session.setAttribute(ConsoleSessionFilter.AUTHENTICATED, true)
        session.setAttribute(ConsoleSessionFilter.CSRF_TOKEN, ConsoleSessionFilter.newCsrfToken())
        session.setAttribute(OPERATOR_ID, operator.id)
        session.setAttribute(OPERATOR_NAME, operator.displayName)

        operators.markSeen(operator.id, now)
        return RedirectView(properties.handoff.landingPath)
    }

    /**
     * Three outcomes on screen, out of nine in the log.
     *
     * Only the two a person can act on are named: the link aged out, or it had already been used.
     * Everything else — a bad signature, another product's audience, an unknown issuer, a subject
     * this product has never linked — collapses to one sentence, because telling somebody probing
     * this endpoint WHICH of their guesses was closest is the whole of what they came for.
     */
    private fun refuse(failure: HandoffFailure?): RedirectView {
        val shown =
            when (failure) {
                HandoffFailure.Expired -> "expirado"
                HandoffFailure.AlreadyUsed -> "usado"
                else -> "invalido"
            }
        return RedirectView("$REFUSED_PATH?motivo=$shown")
    }

    /**
     * The page somebody actually lands on, because a person followed a menu item and is looking at
     * a browser window. A problem+json body here would be shown to them as text.
     */
    @GetMapping(REFUSED_PATH)
    fun refused(
        @RequestParam(name = "motivo", required = false) reason: String?,
        model: Model,
    ): String {
        model.addAttribute("reason", if (reason in SHOWN_REASONS) reason else "invalido")
        return "console/entrada-recusada"
    }

    companion object {
        const val HANDOFF_PATH = "/console/entrada"
        const val REFUSED_PATH = "/console/entrada-recusada"
        const val OPERATOR_ID = "shiftCatcherOperatorId"
        const val OPERATOR_NAME = "shiftCatcherOperatorName"

        private val SHOWN_REASONS = setOf("expirado", "usado", "invalido")
        private val securityLogger = LoggerFactory.getLogger(HandoffController::class.java)
    }
}
