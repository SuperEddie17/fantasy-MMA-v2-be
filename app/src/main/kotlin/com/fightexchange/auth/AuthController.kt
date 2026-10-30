package com.fightexchange.auth

import com.fightexchange.common.ApiException
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.token.generator.TokenGenerator
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

@Serdeable
data class RegisterRequest(
    val email: String?,
    val password: String?,
    val displayName: String?,
)

@Serdeable
data class LoginRequest(
    val email: String?,
    val password: String?,
)

@Serdeable
data class UserDto(
    val id: Long,
    val email: String,
    val displayName: String,
    val role: String,
    val createdAt: Instant,
)

@Serdeable
data class TokenDto(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Int,
    val user: UserDto,
)

internal const val USER_ROLE = "ROLE_USER"
internal const val ADMIN_ROLE = "ROLE_ADMIN"

private const val TOKEN_SECONDS = 7 * 24 * 3600

/** Player accounts (plan.md, F3.2): register, log in for a JWT, read the own profile. */
@Controller("/api/v1")
class AuthController(
    private val users: UserService,
    private val tokens: TokenGenerator,
) {
    @Post("/auth/register")
    fun register(
        @Body request: RegisterRequest,
    ): HttpResponse<TokenDto> {
        val user = users.register(request.email, request.password, request.displayName)
        return HttpResponse.created(token(user))
    }

    @Post("/auth/login")
    fun login(
        @Body request: LoginRequest,
    ): HttpResponse<TokenDto> {
        val user =
            users.authenticate(request.email, request.password)
                ?: throw ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "E-mail or password is wrong")
        return HttpResponse.ok(token(user))
    }

    @Get("/me")
    fun me(authentication: Authentication): HttpResponse<UserDto> {
        val user = authentication.name.toLongOrNull()?.let(users::find)
        return if (user == null) HttpResponse.notFound() else HttpResponse.ok(user.dto())
    }

    private fun token(user: User): TokenDto {
        val roles = if (user.role == "ADMIN") listOf(USER_ROLE, ADMIN_ROLE) else listOf(USER_ROLE)
        val authentication = Authentication.build(user.id.toString(), roles)
        val jwt =
            tokens.generateToken(authentication, TOKEN_SECONDS).orElseThrow {
                ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "TOKEN_ERROR", "Token could not be issued")
            }
        return TokenDto(jwt, "Bearer", TOKEN_SECONDS, user.dto())
    }

    private fun User.dto() = UserDto(id, email, displayName, role, createdAt)
}
