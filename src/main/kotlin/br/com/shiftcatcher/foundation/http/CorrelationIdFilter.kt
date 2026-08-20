package br.com.shiftcatcher.foundation.http

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

const val CORRELATION_ID_HEADER = "X-Correlation-Id"
const val CORRELATION_ID_MDC_KEY = "correlationId"
const val REQUEST_RECEIVED_AT_ATTRIBUTE = "requestReceivedAt"

private val validCorrelationId = Regex("^[A-Za-z0-9._-]{1,128}$")

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val supplied = request.getHeader(CORRELATION_ID_HEADER)
        val correlationId = supplied?.takeIf(validCorrelationId::matches) ?: UUID.randomUUID().toString()
        request.setAttribute(CORRELATION_ID_MDC_KEY, correlationId)
        request.setAttribute(REQUEST_RECEIVED_AT_ATTRIBUTE, java.time.Instant.now())
        response.setHeader(CORRELATION_ID_HEADER, correlationId)
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY)
        }
    }
}
