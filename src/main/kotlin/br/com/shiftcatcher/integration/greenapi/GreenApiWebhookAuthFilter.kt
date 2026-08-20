package br.com.shiftcatcher.integration.greenapi

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.foundation.http.CORRELATION_ID_HEADER
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class GreenApiWebhookAuthFilter(
    private val properties: ShiftCatcherProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.requestURI != WEBHOOK_PATH

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val body = request.inputStream.readNBytes(MAX_WEBHOOK_BYTES + 1)
        if (body.size > MAX_WEBHOOK_BYTES) {
            writeProblem(request, response, 413, "Payload too large", "Webhook payload exceeds the configured limit")
            return
        }

        val expected = properties.greenApi.webhookToken
        val presented =
            request
                .getHeader("Authorization")
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring(BEARER_PREFIX_LENGTH)
        if (expected.isBlank() || presented == null || !constantTimeEquals(expected, presented)) {
            writeProblem(request, response, 401, "Unauthorized", "Valid webhook bearer token required")
            return
        }
        filterChain.doFilter(CachedBodyRequest(request, body), response)
    }

    private fun constantTimeEquals(
        expected: String,
        presented: String,
    ): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            presented.toByteArray(StandardCharsets.UTF_8),
        )

    private fun writeProblem(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: Int,
        title: String,
        detail: String,
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
                    "code" to if (status == 401) "WEBHOOK_UNAUTHORIZED" else "INVALID_REQUEST",
                    "correlationId" to response.getHeader(CORRELATION_ID_HEADER),
                    "instance" to request.requestURI,
                ),
            ),
        )
    }

    private companion object {
        const val WEBHOOK_PATH = "/api/v1/webhooks/green-api"
        const val BEARER_PREFIX_LENGTH = 7
        const val MAX_WEBHOOK_BYTES = 256 * 1024
    }
}

private class CachedBodyRequest(
    request: HttpServletRequest,
    private val body: ByteArray,
) : HttpServletRequestWrapper(request) {
    override fun getContentLength(): Int = body.size

    override fun getContentLengthLong(): Long = body.size.toLong()

    override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(body)

    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))
}

private class ByteArrayServletInputStream(
    body: ByteArray,
) : ServletInputStream() {
    private val delegate = ByteArrayInputStream(body)

    override fun read(): Int = delegate.read()

    override fun isFinished(): Boolean = delegate.available() == 0

    override fun isReady(): Boolean = true

    override fun setReadListener(readListener: ReadListener) {
        if (!isFinished) {
            readListener.onDataAvailable()
        }
        if (isFinished) {
            readListener.onAllDataRead()
        }
    }
}
