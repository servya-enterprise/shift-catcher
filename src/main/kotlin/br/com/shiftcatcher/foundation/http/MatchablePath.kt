package br.com.shiftcatcher.foundation.http

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.PathContainer

/**
 * The path Spring will actually match a handler against.
 *
 * Every security filter in this application has to key its decision off this and never off
 * [HttpServletRequest.getRequestURI], because the two disagree and the disagreement is exploitable.
 *
 * The servlet specification forbids `getRequestURI()` from percent-decoding: a request for
 * `/%63onsole/api/board` returns exactly that string. Spring's `PathPattern` matches against
 * `PathSegment.valueToMatch()`, which **is** decoded, so the same request resolves to the handler
 * mapped at `/console/api/board`. A filter that asks `requestURI.startsWith("/console")` answers
 * false, decides the request is none of its business, and steps aside — while the controller runs.
 *
 * That was not hypothetical here. Before this existed, `GET /%63onsole/api/board` returned 200 with
 * every ingested WhatsApp message and every shift opportunity to a caller with no session at all,
 * and `POST /%63onsole/api/opportunities/{id}/claim` executed with no session and no CSRF token.
 * `ConsoleApiControllerTest` pins it.
 *
 * `valueToMatch()` also strips path parameters, so `/console/api/session;x=1` normalises to
 * `/console/api/session` — the other way the raw URI and the matched path drift apart.
 *
 * On an unparseable URI this falls back to the raw one, and that is safe rather than lazy: the
 * fallback fails closed. A URI that `PathContainer.parsePath` cannot read is one Spring cannot read
 * either, so no handler is reached and there is nothing to walk past — while the raw string matches
 * no exemption, so the filter still refuses.
 *
 * The context path is deliberately left in place. `server.servlet.context-path` is unset, so it is
 * empty, and the existing comparisons are all against a context-path-inclusive URI. If one is ever
 * configured, strip it here, in this one function, rather than at six call sites.
 */
internal fun HttpServletRequest.matchablePath(): String =
    runCatching {
        PathContainer
            .parsePath(requestURI)
            .elements()
            .filterIsInstance<PathContainer.PathSegment>()
            .joinToString(separator = "/", prefix = "/") { it.valueToMatch() }
    }.getOrDefault(requestURI)
