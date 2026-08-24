package br.com.shiftcatcher.console

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The console signs in once with the same admin token the API uses, and from then on the browser
 * carries a session id instead.
 *
 * That indirection is the point. The alternative — a page that keeps the token and attaches it to
 * every call — would put a credential inside a document that renders WhatsApp messages written by
 * strangers. The token stays on the server; the browser gets something that is useless anywhere
 * else and expires.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class ConsoleSessionFilter(
    private val properties: ShiftCatcherProperties,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith(CONSOLE_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // A console with no token configured is not an open console; it is a disabled one.
        if (properties.security.adminApiToken.isBlank()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        if (request.requestURI == LOGIN_PATH) {
            filterChain.doFilter(request, response)
            return
        }
        val session = request.getSession(false)
        if (session == null || session.getAttribute(AUTHENTICATED) != true) {
            response.sendRedirect(LOGIN_PATH)
            return
        }
        // SameSite=strict already stops a cross-site POST from ever carrying this cookie. The token
        // is the second lock, for the browsers and proxies that get the first one wrong.
        if (request.method.equals("POST", ignoreCase = true) && !hasValidCsrfToken(request, session)) {
            securityLogger.warn("Rejected a console POST to {} without a matching CSRF token", request.requestURI)
            response.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun hasValidCsrfToken(
        request: HttpServletRequest,
        session: HttpSession,
    ): Boolean {
        val expected = session.getAttribute(CSRF_TOKEN) as? String ?: return false
        val presented = request.getParameter(CSRF_FIELD) ?: return false
        return constantTimeEquals(expected, presented)
    }

    companion object {
        const val CONSOLE_PREFIX = "/console"
        const val LOGIN_PATH = "/console/login"
        const val AUTHENTICATED = "shiftCatcherConsoleAuthenticated"
        const val CSRF_TOKEN = "shiftCatcherConsoleCsrfToken"
        const val CSRF_FIELD = "csrfToken"

        private val securityLogger = LoggerFactory.getLogger(ConsoleSessionFilter::class.java)
        private val random = SecureRandom()

        fun constantTimeEquals(
            expected: String,
            presented: String,
        ): Boolean =
            MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.UTF_8),
                presented.toByteArray(StandardCharsets.UTF_8),
            )

        fun newCsrfToken(): String {
            val bytes = ByteArray(CSRF_TOKEN_BYTES)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private const val CSRF_TOKEN_BYTES = 32
    }
}
