package com.fightexchange.common

import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.server.exceptions.ExceptionHandler
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton

/** Error body, `application/problem+json` (RFC 9457) with a machine readable [code] such as `INSUFFICIENT_CASH`. */
@Serdeable
data class Problem(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val code: String,
    val detail: String? = null,
)

/** A failed request that maps to a [Problem]. */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    val title: String,
    val detail: String? = null,
) : RuntimeException("$code: ${detail ?: title}")

@Singleton
@Requires(classes = [ApiException::class])
class ApiExceptionHandler : ExceptionHandler<ApiException, HttpResponse<Problem>> {
    override fun handle(
        request: HttpRequest<*>,
        exception: ApiException,
    ): HttpResponse<Problem> =
        HttpResponse
            .status<Problem>(exception.status)
            .contentType(MediaType.of("application/problem+json"))
            .body(
                Problem(
                    title = exception.title,
                    status = exception.status.code,
                    code = exception.code,
                    detail = exception.detail,
                ),
            )
}
