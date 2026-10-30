package com.fightexchange.trading

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
import java.time.Instant
import java.time.temporal.ChronoUnit

@MicronautTest(transactional = false)
class TradeApiTest : PostgresTest() {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject lateinit var tx: TransactionOperations<Connection>

    private lateinit var fx: TradingFixture
    private lateinit var token: String
    private var portfolio = 0L

    @BeforeEach
    fun setUp() {
        fx = TradingFixture(tx)
        fx.clean()
        fx.season(
            Instant.now().minus(30, ChronoUnit.DAYS).toString(),
            Instant.now().plus(300, ChronoUnit.DAYS).toString(),
        )
        token = register("ann@example.com")
        portfolio =
            Regex("\"id\":(\\d+)")
                .find(
                    call(HttpRequest.POST("/api/v1/portfolios", emptyMap<String, Any>()).bearerAuth(token)).body()!!,
                )!!
                .groupValues[1]
                .toLong()
    }

    @AfterEach
    fun tearDown() = fx.clean()

    private fun call(request: HttpRequest<*>) = client.toBlocking().exchange(request, String::class.java)

    private fun register(email: String) =
        Regex("\"accessToken\":\"([^\"]+)\"")
            .find(
                call(
                    HttpRequest.POST(
                        "/api/v1/auth/register",
                        mapOf("email" to email, "password" to "correct-horse", "displayName" to "P"),
                    ),
                ).body()!!,
            )!!
            .groupValues[1]

    private fun post(
        uri: String,
        body: Any,
        bearer: String = token,
    ) = call(HttpRequest.POST(uri, body).bearerAuth(bearer))

    private fun trade(
        kind: String,
        fighter: Long,
        key: String? = null,
    ) = post(
        "/api/v1/portfolios/$portfolio/trades",
        buildMap {
            put("kind", kind)
            put("fighterId", fighter)
            if (key != null) put("idempotencyKey", key)
        },
    )

    private fun problem(block: () -> Unit): Triple<HttpStatus, String, String> {
        val e = assertThrows(HttpClientResponseException::class.java, block)
        val body = e.response.getBody(String::class.java).orElse("")
        return Triple(
            e.status,
            Regex("\"code\":\"([A-Z_]+)\"").find(body)?.groupValues?.get(1) ?: "",
            e.response.contentType
                .map { it.toString() }
                .orElse(""),
        )
    }

    @Test
    fun `a trade is booked and visible in the portfolio, trades and ledger`() {
        val f = fx.fighter("Alpha")
        val response = trade("BUY", f)
        assertEquals(HttpStatus.CREATED, response.status)
        assertTrue(response.body()!!.contains("\"cash\":93940000"), response.body())
        val view = call(HttpRequest.GET<Any>("/api/v1/portfolios/$portfolio").bearerAuth(token)).body()!!
        assertTrue(view.contains("\"cash\":93940000") && view.contains("\"longValue\":6000000"), view)
        assertTrue(view.contains("\"netWorth\":99940000"), view)
        val trades = call(HttpRequest.GET<Any>("/api/v1/portfolios/$portfolio/trades").bearerAuth(token)).body()!!
        assertTrue(trades.contains("\"kind\":\"BUY\"") && trades.contains("Alpha"), trades)
        val ledger = call(HttpRequest.GET<Any>("/api/v1/portfolios/$portfolio/ledger").bearerAuth(token)).body()!!
        assertTrue(ledger.contains("\"kind\":\"FEE\"") && ledger.contains("\"kind\":\"TRADE\""), ledger)
    }

    @Test
    fun `quote previews the trade without booking it`() {
        val f = fx.fighter("Alpha")
        val body = post("/api/v1/portfolios/$portfolio/quote", mapOf("kind" to "BUY", "fighterId" to f)).body()!!
        assertTrue(body.contains("\"cashChange\":-6060000") && body.contains("\"allowed\":true"), body)
        assertEquals(0, fx.query("SELECT 1 FROM trade").size)
    }

    @Test
    fun `a refused trade is a problem with a code and a quote lists the violations`() {
        val rich = fx.fighter("Rich", 150_000_000)
        val (status, code, type) = problem { trade("BUY", rich) }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, status)
        assertEquals("INSUFFICIENT_CASH", code)
        assertEquals("application/problem+json", type)
        val quote = post("/api/v1/portfolios/$portfolio/quote", mapOf("kind" to "BUY", "fighterId" to rich)).body()!!
        assertTrue(quote.contains("\"allowed\":false") && quote.contains("INSUFFICIENT_CASH"), quote)
        assertEquals(
            Triple(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "application/problem+json"),
            problem {
                trade("GIFT", rich)
            },
        )
        assertEquals(HttpStatus.NOT_FOUND, problem { trade("BUY", rich + 999) }.first)
    }

    @Test
    fun `a repeated idempotency key answers 200 with the stored trade`() {
        val f = fx.fighter("Alpha")
        val first = trade("BUY", f, "once")
        val second = trade("BUY", f, "once")
        assertEquals(HttpStatus.CREATED, first.status)
        assertEquals(HttpStatus.OK, second.status)
        assertTrue(second.body()!!.contains("\"replayed\":true"), second.body())
        assertEquals(1, fx.query("SELECT 1 FROM trade").size)
    }

    @Test
    fun `only the owner trades and an anonymous call is rejected`() {
        val f = fx.fighter("Alpha")
        val bob = register("bob@example.com")
        assertEquals(
            HttpStatus.NOT_FOUND,
            problem {
                post(
                    "/api/v1/portfolios/$portfolio/trades",
                    mapOf("kind" to "BUY", "fighterId" to f),
                    bob,
                )
            }.first,
        )
        assertEquals(
            HttpStatus.NOT_FOUND,
            problem {
                post(
                    "/api/v1/portfolios/$portfolio/quote",
                    mapOf("kind" to "BUY", "fighterId" to f),
                    bob,
                )
            }.first,
        )
        val anonymous =
            assertThrows(HttpClientResponseException::class.java) {
                call(HttpRequest.POST("/api/v1/portfolios/$portfolio/trades", mapOf("kind" to "BUY", "fighterId" to f)))
            }
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.status)
        assertFalse(fx.query("SELECT 1 FROM trade").isNotEmpty())
    }
}
