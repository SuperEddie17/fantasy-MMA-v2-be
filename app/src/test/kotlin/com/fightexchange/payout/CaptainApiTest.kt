package com.fightexchange.payout

import com.fightexchange.PostgresTest
import com.fightexchange.ranking.RankingJobs
import com.fightexchange.trading.TradingFixture
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Captain, dividends, net worth and leaderboard endpoints. */
@MicronautTest(transactional = false)
class CaptainApiTest : PostgresTest() {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject lateinit var tx: TransactionOperations<Connection>

    @Inject lateinit var jobs: RankingJobs

    private lateinit var fx: TradingFixture

    @BeforeEach
    fun setUp() {
        fx = TradingFixture(tx)
        fx.cleanAll()
        fx.season(
            Instant.now().minus(2, ChronoUnit.DAYS).toString(),
            Instant.now().plus(100, ChronoUnit.DAYS).toString(),
        )
    }

    @AfterEach
    fun tearDown() = fx.cleanAll()

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
                    mapOf("email" to "cap$n@example.com", "password" to "correct-horse", "displayName" to "Cap $n"),
                ),
            ).body()!!
        val token = field(registered, "accessToken")
        val portfolio = call(HttpRequest.POST("/api/v1/portfolios", emptyMap<String, Any>()).bearerAuth(token)).body()!!
        return Player(token, field(portfolio, "id").toLong())
    }

    private fun code(block: () -> Unit): Pair<HttpStatus, String> {
        val e = assertThrows(HttpClientResponseException::class.java) { block() }
        val body = e.response.getBody(String::class.java).orElse("")
        return e.status to
            Regex("\"code\":\"([A-Z_]+)\"")
                .find(body)
                ?.groupValues
                ?.get(1)
                .orEmpty()
    }

    private fun put(
        p: Player,
        event: Long,
        fighter: Long?,
    ) = call(
        HttpRequest
            .PUT("/api/v1/portfolios/${p.portfolio}/captain/$event", mapOf("fighterId" to fighter))
            .bearerAuth(p.token),
    )

    @Test
    fun `captain picks follow the holding, the card and the weigh-in`() {
        val p = join(1)
        val held = fx.fighter("Held")
        val other = fx.fighter("Other")
        val unheld = fx.fighter("Unheld")
        val open = fx.event(Instant.now().plus(3, ChronoUnit.DAYS), Instant.now().plus(2, ChronoUnit.DAYS))
        fx.bout(open, held, other)
        val closed = fx.event(Instant.now().plus(1, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.HOURS))
        fx.bout(closed, held, unheld)
        fx.exec(
            "INSERT INTO position_lot (portfolio_id, fighter_id, side, opened_at, open_price) " +
                "VALUES (${p.portfolio}, $held, 'LONG', now(), 5000000)",
        )

        val ok = put(p, open, held)
        assertEquals(HttpStatus.OK, ok.status)
        assertEquals(held.toString(), field(ok.body()!!, "fighterId"))
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY to "FIGHTER_NOT_HELD", code { put(p, open, other) })
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY to "FIGHTER_NOT_ON_CARD", code { put(p, open, unheld) })
        assertEquals(HttpStatus.CONFLICT to "CAPTAIN_LOCKED", code { put(p, closed, held) })
        assertEquals(HttpStatus.NOT_FOUND to "EVENT_NOT_FOUND", code { put(p, 999_999, held) })
        assertEquals(HttpStatus.BAD_REQUEST to "VALIDATION_ERROR", code { put(p, open, null) })

        val list = call(HttpRequest.GET<Any>("/api/v1/portfolios/${p.portfolio}/captain").bearerAuth(p.token)).body()!!
        assertEquals(1, Regex("\"eventId\"").findAll(list).count())

        // somebody else's portfolio does not exist for me
        val intruder = join(2)
        val denied =
            code {
                call(HttpRequest.GET<Any>("/api/v1/portfolios/${p.portfolio}/dividends").bearerAuth(intruder.token))
            }
        assertEquals(HttpStatus.NOT_FOUND to "PORTFOLIO_NOT_FOUND", denied)
    }

    @Test
    fun `dividends, net worth and the leaderboard are served`() {
        val p = join(1)
        val q = join(2)
        fx.exec("UPDATE portfolio SET cash_cached = cash_cached + 10000000 WHERE id = ${q.portfolio}")
        val f = fx.fighter("F")
        fx.exec(
            "INSERT INTO position_lot (portfolio_id, fighter_id, side, opened_at, open_price) " +
                "VALUES (${p.portfolio}, $f, 'LONG', now(), 5000000)",
        )

        val dividends =
            call(HttpRequest.GET<Any>("/api/v1/portfolios/${p.portfolio}/dividends").bearerAuth(p.token)).body()!!
        assertTrue(!dividends.contains("amount"), dividends)

        val now = Instant.now()
        jobs.runNetWorth(now)
        jobs.runLeaderboard(now)

        val history =
            call(
                HttpRequest.GET<Any>("/api/v1/portfolios/${p.portfolio}/net-worth").bearerAuth(p.token),
            ).body()!!
        assertEquals(1, Regex("\"netWorth\"").findAll(history).count())
        assertEquals(
            HttpStatus.BAD_REQUEST to "VALIDATION_ERROR",
            code {
                call(
                    HttpRequest.GET<Any>("/api/v1/portfolios/${p.portfolio}/net-worth?from=nope").bearerAuth(p.token),
                )
            },
        )

        val board = call(HttpRequest.GET<Any>("/api/v1/leaderboard")).body()!!
        assertTrue(board.indexOf("\"rank\":1") in 0 until board.indexOf("\"rank\":2"), board)
        assertEquals(q.portfolio.toString(), Regex("\"portfolioId\":(\\d+)").find(board)!!.groupValues[1])
        val month = call(HttpRequest.GET<Any>("/api/v1/leaderboard?period=SEASON&limit=1")).body()!!
        assertTrue(month.contains("\"nextOffset\":1"), month)
        assertEquals(
            HttpStatus.BAD_REQUEST to "VALIDATION_ERROR",
            code { call(HttpRequest.GET<Any>("/api/v1/leaderboard?period=WEEK")) },
        )
    }
}
