package com.fightexchange.admin

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.security.authentication.AuthenticationFailureReason
import io.micronaut.security.authentication.AuthenticationRequest
import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.authentication.provider.HttpRequestAuthenticationProvider
import jakarta.inject.Singleton
import java.security.MessageDigest

/** The one static admin account (`admin.username` / `admin.password`); a blank password disables login. */
@Singleton
class AdminAuthProvider(
    @Property(name = "admin.username") private val username: String,
    @Property(name = "admin.password") private val password: String,
) : HttpRequestAuthenticationProvider<Any> {
    override fun authenticate(
        requestContext: HttpRequest<Any>?,
        authRequest: AuthenticationRequest<String, String>,
    ): AuthenticationResponse {
        val ok =
            password.isNotBlank() &&
                constantTimeEquals(authRequest.identity, username) &&
                constantTimeEquals(authRequest.secret, password)
        return if (ok) {
            AuthenticationResponse.success(username, listOf(ADMIN_ROLE))
        } else {
            AuthenticationResponse.failure(AuthenticationFailureReason.CREDENTIALS_DO_NOT_MATCH)
        }
    }

    private fun constantTimeEquals(
        a: String?,
        b: String,
    ) = a != null && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val ADMIN_ROLE = "ROLE_ADMIN"
    }
}
