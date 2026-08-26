package br.com.shiftcatcher.foundation.http

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals

/**
 * The two ways a raw URI and the path Spring matches can disagree, pinned separately.
 *
 * Both were live. The encoding half handed the whole board to an anonymous caller; the context-path
 * half turned every security filter in the application off at once, for anyone who could set a
 * header, because `forward-headers-strategy: framework` folds `X-Forwarded-Prefix` into the context
 * path before our filters run.
 */
class MatchablePathTest {
    private fun request(
        uri: String,
        contextPath: String = "",
    ): MockHttpServletRequest =
        MockHttpServletRequest("GET", uri).apply {
            requestURI = uri
            this.contextPath = contextPath
        }

    @Test
    fun `decodes what the servlet API refuses to decode`() {
        // getRequestURI() is raw by specification; PathSegment.valueToMatch() is decoded. A filter
        // testing the raw string let /%63onsole/api/board past and the handler still ran.
        assertEquals("/console/api/board", request("/%63onsole/api/board").matchablePath())
        assertEquals("/console/api/session", request("/console/api/%73ession").matchablePath())
    }

    @Test
    fun `subtracts the context path, which an attacker can set`() {
        // ForwardedHeaderFilter runs at HIGHEST_PRECEDENCE and rewrites the request so that
        // X-Forwarded-Prefix becomes the context path. Our filters run inside it, so before this
        // they saw "/x/console/api/board", matched no prefix, and stepped aside — while
        // DispatcherServlet matched the handler at "/console/api/board".
        assertEquals("/console/api/board", request("/x/console/api/board", "/x").matchablePath())
        assertEquals("/api/v1/claims", request("/x/api/v1/claims", "/x").matchablePath())
        assertEquals(
            "/api/v1/webhooks/green-api",
            request("/x/api/v1/webhooks/green-api", "/x").matchablePath(),
        )
    }

    @Test
    fun `handles both at once, because an attacker would`() {
        assertEquals("/console/api/board", request("/%78/%63onsole/api/board", "/%78").matchablePath())
    }

    @Test
    fun `strips path parameters, the third way the two strings drift`() {
        assertEquals("/console/api/session", request("/console/api/session;x=1").matchablePath())
    }

    @Test
    fun `leaves an ordinary path alone`() {
        assertEquals("/console/api/board", request("/console/api/board").matchablePath())
        assertEquals("/console/login", request("/console/login").matchablePath())
        assertEquals("/", request("/").matchablePath())
    }

    @Test
    fun `fails closed on a URI it cannot read`() {
        // An unparseable URI is one Spring cannot parse either, so no handler is reached. Returning
        // the raw string matches no exemption, so the filter refuses rather than standing aside.
        val raw = "/%ZZ/console/api/board"
        assertEquals(raw, request(raw).matchablePath())
    }
}
