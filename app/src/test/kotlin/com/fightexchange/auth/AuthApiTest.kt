package com.fightexchange.auth

import com.fightexchange.PostgresTest
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection

@MicronautTest(transactional = false)
class AuthApiTest : PostgresTest() {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject lateinit var tx: TransactionOperations<Connection>

    @BeforeEach
    @AfterEach
    fun clean() {
        tx.executeWrite { it.connection.createStatement().use { s -> s.execute("DELETE FROM app_user") } }
    }

    private fun post(
        uri: String,
        body: Any,
        token: String? = null,
    ) = client.toBlocking().exchange(
        HttpRequest.POST(uri, body).also { if (token != null) it.bearerAuth(token) },
        String::class.java,
    )

    private fun status(block: () -> Unit): HttpStatus =
        assertThrows(
            HttpClientResponseException::class.java,
            block,
        ).status

    private fun register(email: String = "ann@example.com") =
        post(
            "/api/v1/auth/register",
            mapOf("email" to email, "password" to "correct-horse", "displayName" to "Ann"),
        )

    private fun tokenOf(body: String) = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    @Test
    fun `register returns a token that opens the profile`() {
        val response = register()
        assertEquals(HttpStatus.CREATED, response.status)
        val token = tokenOf(response.body()!!)
        val me =
            client
                .toBlocking()
                .retrieve(HttpRequest.GET<Any>("/api/v1/me").bearerAuth(token), String::class.java)
        assertTrue(me.contains("\"email\":\"ann@example.com\""), me)
        assertTrue(me.contains("\"displayName\":\"Ann\""), me)
        assertFalse(me.contains("password"), me)
    }

    @Test
    fun `login accepts the e-mail in any case and refuses a wrong password`() {
        register()
        val ok = post("/api/v1/auth/login", mapOf("email" to "ANN@example.com", "password" to "correct-horse"))
        assertEquals(HttpStatus.OK, ok.status)
        assertTrue(ok.body()!!.contains("\"tokenType\":\"Bearer\""))
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            status { post("/api/v1/auth/login", mapOf("email" to "ann@example.com", "password" to "wrong-password")) },
        )
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            status {
                post(
                    "/api/v1/auth/login",
                    mapOf("email" to "nobody@example.com", "password" to "correct-horse"),
                )
            },
        )
    }

    @Test
    fun `a registered e-mail cannot register again and bad input is a problem`() {
        register()
        val dup = assertThrows(HttpClientResponseException::class.java) { register("Ann@Example.com") }
        assertEquals(HttpStatus.CONFLICT, dup.status)
        assertTrue(
            dup.response
                .getBody(String::class.java)
                .get()
                .contains("\"code\":\"EMAIL_TAKEN\""),
        )
        assertEquals(
            "application/problem+json",
            dup.response.contentType
                .get()
                .toString(),
        )
        val short =
            assertThrows(HttpClientResponseException::class.java) {
                post(
                    "/api/v1/auth/register",
                    mapOf("email" to "b@example.com", "password" to "short", "displayName" to "B"),
                )
            }
        assertEquals(HttpStatus.BAD_REQUEST, short.status)
        assertTrue(
            short.response
                .getBody(String::class.java)
                .get()
                .contains("VALIDATION_ERROR"),
        )
        assertEquals(
            HttpStatus.BAD_REQUEST,
            status {
                post(
                    "/api/v1/auth/register",
                    mapOf(
                        "email" to "not-an-email",
                        "password" to "correct-horse",
                        "displayName" to "B",
                    ),
                )
            },
        )
    }

    @Test
    fun `profile needs a token and a player token does not open the admin api`() {
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            status { client.toBlocking().retrieve(HttpRequest.GET<Any>("/api/v1/me"), String::class.java) },
        )
        val token = tokenOf(register().body()!!)
        assertEquals(
            HttpStatus.FORBIDDEN,
            status {
                client.toBlocking().retrieve(
                    HttpRequest.GET<Any>("/api/v1/admin/review").bearerAuth(token),
                    String::class.java,
                )
            },
        )
    }
}
