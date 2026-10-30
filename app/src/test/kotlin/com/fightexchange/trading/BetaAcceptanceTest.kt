package com.fightexchange.trading

import com.fightexchange.PostgresTest
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Phase 3 acceptance: a closed beta of 15 players builds portfolios over the API, all at once. */
@MicronautTest(transactional = false)
class BetaAcceptanceTest :
    PostgresTest(),
    TestPropertyProvider {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject lateinit var tx: TransactionOperations<Connection>

    override fun getProperties(): Map<String, String> = super.getProperties() + ("admin.password" to "secret")

    private lateinit var fx: TradingFixture

    @BeforeEach
    fun setUp() {
        fx = TradingFixture(tx)
        fx.clean()
    }

    @AfterEach
    fun tearDown() = fx.clean()

    private fun call(request: HttpRequest<*>) = client.toBlocking().exchange(request, String::class.java)

    private fun field(
        body: String,
        name: String,
    ) = Regex("\"$name\":\"?([^\",}]+)").find(body)!!.groupValues[1]

    private class Player(
        val token: String,
        val portfolio: Long,
    )

    private fun join(n: Int): Player {
        val registered =
            call(
                HttpRequest.POST(
                    "/api/v1/auth/register",
                    mapOf("email" to "player$n@example.com", "password" to "correct-horse", "displayName" to "P$n"),
                ),
            ).body()!!
        val token = field(registered, "accessToken")
        val portfolio = call(HttpRequest.POST("/api/v1/portfolios", emptyMap<String, Any>()).bearerAuth(token)).body()!!
        return Player(token, field(portfolio, "id").toLong())
    }

    private fun trade(
        p: Player,
        kind: String,
        fighter: Long,
    ) = call(
        HttpRequest
            .POST("/api/v1/portfolios/${p.portfolio}/trades", mapOf("kind" to kind, "fighterId" to fighter))
            .bearerAuth(p.token),
    )

    @Test
    fun `fifteen players build portfolios at once, the ledger agrees and a weigh-in closes trading`() {
        fx.season(
            Instant.now().minus(1, ChronoUnit.DAYS).toString(),
            Instant.now().plus(300, ChronoUnit.DAYS).toString(),
        )
        val ufc = (1..14).map { fx.fighter("U$it", 3_000_000L + it * 200_000) }
        val other = (1..6).map { fx.fighter("O$it", 1_500_000L + it * 100_000, if (it % 2 == 0) "PFL" else "OKT") }
        val players = (1..15).map { join(it) }

        // Every player buys 8 UFC and 3 non-UFC fighters; players start at different fighters so they overlap.
        val pool = Executors.newFixedThreadPool(15)
        val done =
            players.mapIndexed { i, p ->
                pool.submit {
                    val picks =
                        (0 until 8).map { ufc[(i + it) % ufc.size] } + (0 until 3).map { other[(i + it) % other.size] }
                    picks.forEach { trade(p, "BUY", it) }
                    trade(p, "SELL", picks.first())
                    trade(p, "SHORT_OPEN", ufc[(i + 10) % ufc.size])
                }
            }
        done.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        players.forEach { p ->
            val view = call(HttpRequest.GET<Any>("/api/v1/portfolios/${p.portfolio}").bearerAuth(p.token)).body()!!
            assertEquals(10, Regex("\"side\":\"LONG\"").findAll(view).count(), view)
            assertEquals(1, Regex("\"side\":\"SHORT\"").findAll(view).count(), view)
            assertEquals(fx.cashCached(p.portfolio), field(view, "cash").toLong())
            assertEquals(fx.cashCached(p.portfolio), fx.ledgerSum(p.portfolio))
            assertTrue(fx.cashCached(p.portfolio) > 0)
        }
        assertEquals(15 * 13, fx.query("SELECT 1 FROM trade").size)

        // the nightly ledger check finds nothing
        val admin =
            field(
                call(HttpRequest.POST("/login", mapOf("username" to "admin", "password" to "secret"))).body()!!,
                "access_token",
            )
        val check = call(HttpRequest.GET<Any>("/api/v1/admin/ledger/check").bearerAuth(admin)).body()!!
        assertEquals("[]", check)

        // a bout whose weigh-in has passed closes trading in both fighters until the result is confirmed
        val a = fx.fighter("Locked A")
        val b = fx.fighter("Locked B")
        val event = fx.event(Instant.now().plus(1, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.HOURS))
        val bout = fx.bout(event, a, b)
        val e = assertThrows(HttpClientResponseException::class.java) { trade(players[0], "BUY", a) }
        assertEquals(HttpStatus.CONFLICT, e.status)
        assertTrue(
            e.response
                .getBody(String::class.java)
                .get()
                .contains("\"code\":\"TRADING_LOCKED\""),
        )
        fx.exec("UPDATE bout SET status = 'COMPLETED' WHERE id = $bout")
        assertEquals(HttpStatus.CREATED, trade(players[0], "BUY", a).status)
    }
}
