package br.com.shiftcatcher.console

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.foundation.http.matchablePath
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
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
 *
 * Two front doors sit behind this filter now, and they fail differently. The server-rendered pages
 * answer a browser navigation, so an unauthenticated request is met with a redirect to the login
 * page. The paths under the API prefix answer a fetch, and a redirect there is worse than useless:
 * the browser follows it transparently and the caller receives 200 with a login page in the body.
 * So the API prefix gets application/problem+json with a status the client can branch on.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class ConsoleSessionFilter(
    private val properties: ShiftCatcherProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    // matchablePath(), never requestURI: the raw URI is not decoded and the path Spring matches a
    // handler against is. Testing the raw one let /%63onsole/api/board walk past this filter and
    // reach the controller with no session at all.
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.matchablePath().startsWith(CONSOLE_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.matchablePath()
        val json = path.startsWith(API_PREFIX)

        // A console with no token configured is not an open console; it is a disabled one.
        if (properties.security.adminApiToken.isBlank()) {
            if (json) {
                problem(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", "Console disabled", DISABLED, request)
            } else {
                response.sendError(HttpServletResponse.SC_NOT_FOUND)
            }
            return
        }
        if (path == LOGIN_PATH) {
            filterChain.doFilter(request, response)
            return
        }
        // Sign-in is the one call that arrives without a session and is expected to. The exemption
        // is an exact URI-and-method match rather than a prefix, so nothing else inherits it: GET
        // and DELETE on the same path still require a session.
        if (json && path == SESSION_PATH && request.method.equals("POST", ignoreCase = true)) {
            filterChain.doFilter(request, response)
            return
        }
        val session = request.getSession(false)
        if (session == null || session.getAttribute(AUTHENTICATED) != true) {
            if (json) {
                problem(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Sign-in required",
                    "This console session has expired or was never established",
                    request,
                )
            } else {
                response.sendRedirect(LOGIN_PATH)
            }
            return
        }
        // SameSite=strict already stops a cross-site POST from ever carrying this cookie. The token
        // is the second lock, for the browsers and proxies that get the first one wrong.
        //
        // Every unsafe method, not only POST: the JSON front door uses PUT and DELETE, and a check
        // that names one verb protects one verb.
        if (isUnsafe(request.method) && !hasValidCsrfToken(request, session)) {
            securityLogger.warn(
                "Rejected a console {} to {} without a matching CSRF token",
                request.method,
                request.requestURI,
            )
            if (json) {
                problem(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "CSRF_VALIDATION_FAILED",
                    "Missing or stale CSRF token",
                    "Fetch the session again to obtain a current token",
                    request,
                )
            } else {
                response.sendError(HttpServletResponse.SC_FORBIDDEN)
            }
            return
        }
        filterChain.doFilter(request, response)
    }

    /**
     * The header first, the form field second.
     *
     * The server-rendered console posts a hidden field; a fetch cannot set one without building a
     * form body, and the single-page app sends JSON. Both are accepted, and both are compared in
     * constant time.
     */
    private fun hasValidCsrfToken(
        request: HttpServletRequest,
        session: HttpSession,
    ): Boolean {
        val expected = session.getAttribute(CSRF_TOKEN) as? String ?: return false
        val presented = request.getHeader(CSRF_HEADER) ?: request.getParameter(CSRF_FIELD) ?: return false
        return constantTimeEquals(expected, presented)
    }

    /**
     * Writes the problem document itself rather than delegating to sendError.
     *
     * The application sets server.error.include-message to never, which strips the message from the
     * container's error page, and the page that comes back may well be HTML. A client that has to
     * parse JSON to learn why it was refused cannot be handed either.
     */
    private fun problem(
        response: HttpServletResponse,
        status: Int,
        code: String,
        title: String,
        detail: String,
        request: HttpServletRequest,
    ) {
        response.status = status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf(
                    "type" to "about:blank",
                    "title" to title,
                    "status" to status,
                    "detail" to detail,
                    "instance" to request.requestURI,
                    "code" to code,
                ),
            ),
        )
    }

    private fun isUnsafe(method: String): Boolean = !SAFE_METHODS.contains(method.uppercase())

    companion object {
        const val CONSOLE_PREFIX = "/console"
        const val API_PREFIX = "/console/api/"
        const val SESSION_PATH = "/console/api/session"
        const val LOGIN_PATH = "/console/login"
        const val AUTHENTICATED = "shiftCatcherConsoleAuthenticated"
        const val CSRF_TOKEN = "shiftCatcherConsoleCsrfToken"
        const val CSRF_FIELD = "csrfToken"
        const val CSRF_HEADER = "X-CSRF-Token"

        private const val DISABLED = "The operator console has no admin token configured"
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")
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
