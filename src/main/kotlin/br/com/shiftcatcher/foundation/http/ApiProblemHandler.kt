package br.com.shiftcatcher.foundation.http

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class ApiProblemHandler {
    @ExceptionHandler(ApiProblemException::class)
    fun apiProblem(
        exception: ApiProblemException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(
            status = exception.status,
            title = exception.title,
            detail = exception.message,
            code = exception.code,
            request = request,
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableRequest(
        exception: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ProblemDetail = invalidRequest(IllegalArgumentException("Malformed JSON request"), request)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidRequest(
        exception: IllegalArgumentException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(
            status = HttpStatus.BAD_REQUEST,
            title = "Invalid request",
            detail = exception.message ?: "Invalid request",
            code = "INVALID_REQUEST",
            request = request,
        )

    @ExceptionHandler(Exception::class)
    fun unexpectedFailure(
        exception: Exception,
        request: HttpServletRequest,
    ): ProblemDetail {
        logger.error("Unhandled request failure", exception)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            title = "Unexpected error",
            detail = "The request could not be completed",
            code = "INTERNAL_ERROR",
            request = request,
        )
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
        code: String,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.title = title
            instance = URI.create(request.requestURI)
            setProperty("code", code)
            setProperty("correlationId", request.getAttribute(CORRELATION_ID_MDC_KEY) ?: request.correlationId())
        }

    private fun HttpServletRequest.correlationId(): String = getHeader(CORRELATION_ID_HEADER) ?: "unavailable"

    private companion object {
        val logger = LoggerFactory.getLogger(ApiProblemHandler::class.java)
    }
}

class ApiProblemException(
    val status: HttpStatus,
    val code: String,
    val title: String,
    override val message: String,
) : RuntimeException(message)
