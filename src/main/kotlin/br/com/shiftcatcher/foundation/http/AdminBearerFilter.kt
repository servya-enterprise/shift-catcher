package br.com.shiftcatcher.foundation.http

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AdminBearerFilter(
    private val properties: ShiftCatcherProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith(API_PREFIX) || request.requestURI == WEBHOOK_PATH

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val expected = properties.security.adminApiToken
        val presented =
            request
                .getHeader("Authorization")
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring(BEARER_PREFIX_LENGTH)
        if (expected.isBlank() || presented == null || !constantTimeEquals(expected, presented)) {
            writeUnauthorized(request, response)
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun constantTimeEquals(
        expected: String,
        presented: String,
    ): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            presented.toByteArray(StandardCharsets.UTF_8),
        )

    private fun writeUnauthorized(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val correlationId = response.getHeader(CORRELATION_ID_HEADER)
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf(
                    "type" to "about:blank",
                    "title" to "Unauthorized",
                    "status" to 401,
                    "detail" to "Valid admin bearer token required",
                    "code" to "INVALID_REQUEST",
                    "correlationId" to correlationId,
                    "instance" to request.requestURI,
                ),
            ),
        )
    }

    private companion object {
        const val API_PREFIX = "/api/v1/"
        const val WEBHOOK_PATH = "/api/v1/webhooks/green-api"
        const val BEARER_PREFIX_LENGTH = 7
    }
}
