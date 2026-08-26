package br.com.shiftcatcher.foundation.http

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.PathContainer
import org.springframework.http.server.RequestPath

/**
 * The path Spring will actually match a handler against.
 *
 * Every security filter in this application has to key its decision off this and never off
 * [HttpServletRequest.getRequestURI], because the two disagree in two independent ways and both are
 * exploitable.
 *
 * **Encoding.** The servlet specification forbids `getRequestURI()` from percent-decoding, so a
 * request for `/%63onsole/api/board` returns exactly that string, while `PathPattern` matches
 * against `PathSegment.valueToMatch()`, which is decoded. A filter asking
 * `requestURI.startsWith("/console")` answers false, decides the request is none of its business,
 * and steps aside — while the controller runs. Measured: that returned 200 with every ingested
 * WhatsApp message and every shift opportunity to a caller with no session.
 *
 * **The context path.** `getRequestURI()` includes it; `DispatcherServlet` matches against
 * `pathWithinApplication()`, which does not. That would be a dormant difference — the context path
 * is empty by default — except that `application.yml` sets `forward-headers-strategy: framework`,
 * which puts Spring's `ForwardedHeaderFilter` at the very front of the chain, and that filter
 * rewrites the request so `X-Forwarded-Prefix` becomes part of the context path. The prefix comes
 * from whoever sent the header. So `GET /console/api/board` with `X-Forwarded-Prefix: /x` produced
 * a `requestURI` of `/x/console/api/board` — no prefix match, filter steps aside — and a handler
 * path of `/console/api/board`. Every filter in the application went off at once, including both
 * of the ones guarding the GREEN-API webhook, which then accepted unsigned payloads.
 *
 * The first version of this helper closed the encoding half and left the context-path half open,
 * with a comment saying it could be dealt with later if a context path were ever configured. It was
 * already configured, by anyone who could set a header.
 *
 * `RequestPath.parse` handles both: it subtracts the context path and decodes the segments.
 * `valueToMatch()` also strips path parameters, so `/console/api/session;x=1` normalises too.
 *
 * On an unparseable URI this falls back to the raw one, and that is safe rather than lazy: the
 * fallback fails closed. A URI `RequestPath.parse` cannot read is one Spring cannot read either, so
 * no handler is reached and there is nothing to walk past — while the raw string matches no
 * exemption, so the filter still refuses.
 *
 * Nothing here removes the need to stop the header at the edge. `framework` also trusts
 * `X-Forwarded-Host` and `-Proto` from the same source, which reach the console's redirects. The
 * Caddy site blocks must clear `X-Forwarded-Prefix` from anything arriving off the internet.
 */
internal fun HttpServletRequest.matchablePath(): String =
    runCatching {
        RequestPath
            .parse(requestURI, contextPath)
            .pathWithinApplication()
            .elements()
            .filterIsInstance<PathContainer.PathSegment>()
            .joinToString(separator = "/", prefix = "/") { it.valueToMatch() }
    }.getOrDefault(requestURI)
