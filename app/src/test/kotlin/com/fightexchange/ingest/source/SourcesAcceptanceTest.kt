package com.fightexchange.ingest.source

import com.fightexchange.PostgresTest
import com.fightexchange.admin.ResolveResult
import com.fightexchange.admin.ResolveReviewRequest
import com.fightexchange.admin.ReviewService
import com.fightexchange.ingest.ADMIN_SOURCE
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
import com.fightexchange.market.MutableClock
import com.fightexchange.trading.TradingFixture
import com.sun.net.httpserver.HttpServer
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.sql.Connection
import java.time.Instant

private const val START = 100_000_000L

/** Recorded-style answers of both feeds; tests replace the cards and odds between syncs. */
private object Feeds {
    @Volatile var cards: Map<Int, String> = emptyMap()

    @Volatile var odds: String = "[]"

    val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path
                val query = ex.requestURI.query ?: ""
                val body =
                    when {
                        path == "/v1/events" -> list(query)
                        path.startsWith("/v1/events/") -> cards.getValue(path.substringAfterLast('/').toInt())
                        path == "/v1/rankings/ufc" -> RANKINGS
                        path.startsWith("/v4/sports/") -> odds
                        else -> "{}"
                    }.toByteArray()
                ex.responseHeaders.add("X-RateLimit-Remaining", "800")
                ex.responseHeaders.add("x-requests-remaining", "19000")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    private const val RANKINGS = """{"data":{"snapshot_date":"2027-02-01","divisions":[]},"meta":{}}"""

    private fun list(query: String): String {
        val id =
            if ("org=ufc" in query) {
                10
            } else if ("org=oktagon" in query) {
                20
            } else {
                null
            }
        val data = if (id != null && id in cards) """{"id":$id,"status":"scheduled"}""" else ""
        return """{"data":[$data],"meta":{"pagination":{"has_more":false,"next_cursor":null}}}"""
    }

    fun fight(
        id: Int,
        a: Int,
        b: Int,
        status: String = "announced",
        extra: String = "",
    ) = """{"id":$id,"ordering":1,"is_title":false,"is_main":true,"weight_class":"Lightweight","status":"$status",
        |"fighter_a":{"id":$a,"name":"Fighter $a","nickname":null,"country_code":"US"},
        |"fighter_b":{"id":$b,"name":"Fighter $b","nickname":null,"country_code":"CZ"}$extra}
        """.trimMargin()

    fun card(
        id: Int,
        title: String,
        startsAt: String,
        fight: String,
    ) = """{"data":{"id":$id,"title":"$title","status":"scheduled","starts_at":"$startsAt",
        |"card":[$fight]},"meta":{}}
        """.trimMargin()

    fun result(
        winner: Int,
        bonus: String? = null,
    ) = (if (bonus != null) ""","bonus":"$bonus"""" else "") +
        ""","result":{"winner_fighter_id":$winner,"method_normalized":"ko_tko","round":2,"time":"3:10"}"""

    fun quote(
        home: String,
        homePrice: Double,
        away: String,
        awayPrice: Double,
        commence: String,
    ) = """[{"id":"x","sport_key":"mma_mixed_martial_arts","commence_time":"$commence","home_team":"$home",
        |"away_team":"$away","bookmakers":[{"key":"dk","title":"dk","last_update":"2027-03-01T12:00:00Z",
        |"markets":[{"key":"h2h","outcomes":[{"name":"$home","price":$homePrice},{"name":"$away","price":$awayPrice}]}]}]}]
        """.trimMargin()
}

/**
 * F5.7: a UFC event and an Oktagon event go from the announcement (UFCalendar card) through odds (The Odds API)
 * to dividends with nothing but the admin's confirmation of the result; a KSW event is entered by hand.
 */
@MicronautTest(transactional = false)
class SourcesAcceptanceTest : PostgresTest() {
    @Inject lateinit var tx: TransactionOperations<Connection>

    @Inject lateinit var ufcalendar: UfcalendarAdapter

    @Inject lateinit var oddsApi: TheOddsApiAdapter

    @Inject lateinit var reviews: ReviewService

    @Inject lateinit var service: IngestService

    @Inject lateinit var processor: IngestProcessor

    @Inject lateinit var clock: MutableClock

    override fun getProperties(): Map<String, String> {
        val base = "http://127.0.0.1:${Feeds.server.address.port}"
        return super.getProperties() +
            mapOf(
                "test.clock" to "mutable",
                "ingest.ufcalendar.api-key" to "k",
                "ingest.ufcalendar.base-url" to base,
                "ingest.theoddsapi.api-key" to "k",
                "ingest.theoddsapi.base-url" to base,
            )
    }

    private lateinit var fx: TradingFixture
    private var season = 0L
    private var seq = 0

    @BeforeEach
    fun setUp() {
        fx = TradingFixture(tx)
        fx.cleanAll()
        clock.now = Instant.parse("2027-02-01T12:00:00Z")
        season = fx.season("2027-01-01T00:00:00Z", "2027-12-31T00:00:00Z")
    }

    @AfterEach
    fun tearDown() = fx.cleanAll()

    private fun fighter(external: String) =
        fx.query("SELECT fighter_id FROM fighter_external_id WHERE external_id = '$external'").single().toLong()

    private fun price(fighter: Long) =
        fx.query("SELECT price FROM market_listing WHERE fighter_id = $fighter").single().toLong()

    private fun dividends(portfolio: Long) =
        fx
            .query("SELECT amount FROM dividend_payout WHERE portfolio_id = $portfolio AND reversed_at IS NULL")
            .map { it.toLong() }

    private fun confirmOpenReviews() {
        val ids = fx.query("SELECT id FROM review_item WHERE status = 'OPEN' ORDER BY id").map { it.toLong() }
        ids.forEach { assertEquals(ResolveResult.Done, reviews.resolve(it, ResolveReviewRequest("CONFIRM"), "admin")) }
    }

    @Test
    fun `UFC and Oktagon events run from announcement to dividends with only the result confirmation`() {
        val ufcCard = { fight: String -> Feeds.card(10, "UFC 999", "2027-03-06T22:00:00Z", fight) }
        val oktCard = { fight: String -> Feeds.card(20, "Oktagon 99", "2027-03-13T18:00:00Z", fight) }

        // 1. the feeds announce both bouts
        Feeds.cards = mapOf(10 to ufcCard(Feeds.fight(100, 1, 2)), 20 to oktCard(Feeds.fight(200, 3, 4)))
        ufcalendar.sync()
        assertEquals(2, fx.query("SELECT id FROM bout WHERE status = 'ANNOUNCED'").size)
        val (ufcWinner, ufcLoser, oktWinner, oktLoser) = listOf("1", "2", "3", "4").map { fighter(it) }
        assertTrue(fx.query("SELECT 1 FROM price_event WHERE kind = 'ANNOUNCED'").isNotEmpty())

        // 2. players buy before the announcement is effective in the past (lots opened before it were bought earlier)
        val fillers = (1..6).map { fx.fighter("Filler $it") }
        val holder = fx.portfolio(fx.user("holder@example.com"), season, START)
        (listOf(ufcWinner, oktWinner) + fillers).forEach {
            fx.exec(
                "INSERT INTO position_lot (portfolio_id, fighter_id, side, opened_at, open_price) " +
                    "VALUES ($holder, $it, 'LONG', '2027-01-15T00:00:00Z', 5000000)",
            )
        }
        val shorter = fx.portfolio(fx.user("shorter@example.com"), season, START)
        fx.exec(
            "INSERT INTO position_lot (portfolio_id, fighter_id, side, opened_at, open_price) " +
                "VALUES ($shorter, $ufcLoser, 'SHORT', '2027-01-15T00:00:00Z', 5000000)",
        )

        // 3. odds of the UFC fight arrive from The Odds API and confirm it; an unknown fight is ignored
        clock.now = Instant.parse("2027-03-06T10:00:00Z")
        Feeds.odds = Feeds.quote("Fighter 1", 1.5, "Fighter 2", 2.6, "2027-03-06T22:00:00Z")
        assertEquals(1, oddsApi.sync().submitted)
        assertEquals("CONFIRMED", fx.query("SELECT status FROM bout WHERE external_id = '100'").single())
        assertEquals("0", fx.query("SELECT count(*) FROM review_item").single())

        // 4. results appear in the feed: held for the admin, nobody is paid yet
        clock.now = Instant.parse("2027-03-07T03:00:00Z")
        Feeds.cards =
            mapOf(
                10 to ufcCard(Feeds.fight(100, 1, 2, "completed", Feeds.result(1, "Fight of the Night"))),
                20 to oktCard(Feeds.fight(200, 3, 4)),
            )
        ufcalendar.sync()
        assertEquals("RESULT_CONFIRM", fx.query("SELECT reason FROM review_item WHERE status = 'OPEN'").single())
        assertEquals(emptyList<Long>(), dividends(holder))
        val before = price(ufcWinner)

        // 5. the admin confirms: dividend for the holder, short settled, ledger agrees, Elo moved the price
        confirmOpenReviews()
        assertEquals(1, dividends(holder).size)
        assertTrue(dividends(holder).single() > before * 0.25 * 1.5 / 1.2 - 1)
        assertEquals(1, dividends(shorter).size)
        assertEquals(
            setOf("DIVIDEND"),
            fx.query("SELECT kind FROM ledger_entry WHERE bout_id IS NOT NULL AND portfolio_id = $holder").toSet(),
        )
        assertEquals(fx.ledgerSum(holder), fx.cashCached(holder))
        assertEquals(fx.ledgerSum(shorter), fx.cashCached(shorter))
        assertEquals("COMPLETED", fx.query("SELECT status FROM bout WHERE external_id = '100'").single())

        // 6. the Oktagon card a week later follows the same path (no Odds API quote: the feed odds are optional)
        clock.now = Instant.parse("2027-03-14T03:00:00Z")
        Feeds.cards =
            mapOf(
                10 to Feeds.cards.getValue(10),
                20 to oktCard(Feeds.fight(200, 3, 4, "completed", Feeds.result(3))),
            )
        ufcalendar.sync()
        confirmOpenReviews()
        assertEquals(2, dividends(holder).size)
        assertEquals(fx.ledgerSum(holder), fx.cashCached(holder))
        assertTrue(price(oktWinner) != 0L && price(oktLoser) != 0L)

        // a repeated sync does not pay again
        ufcalendar.sync()
        assertEquals(2, dividends(holder).size)
    }

    private fun send(payload: IngestPayload) {
        service.submit(ADMIN_SOURCE, "ksw${seq++}", payload)
        processor.processPending()
    }

    @Test
    fun `a KSW event entered by the admin settles without any confirmation`() {
        val ref = { id: String -> IngestPayload.FighterRef(externalId = id) }
        send(IngestPayload.FighterUpsert("k1", "Kajetan Kadlec", "M", "KSW", "Welterweight"))
        send(IngestPayload.FighterUpsert("k2", "Mateusz Wolf", "M", "KSW", "Welterweight"))
        send(IngestPayload.EventUpsert("ke", "KSW", "KSW 99", Instant.parse("2027-03-20T18:00:00Z")))
        send(IngestPayload.BoutAnnounced("ke", "kb", ref("k1"), ref("k2"), "MAIN"))
        val winner = fighter("k1")
        val fillers = (1..7).map { fx.fighter("Filler $it") }
        val holder = fx.portfolio(fx.user("ksw@example.com"), season, START)
        (listOf(winner) + fillers).forEach {
            fx.exec(
                "INSERT INTO position_lot (portfolio_id, fighter_id, side, opened_at, open_price) " +
                    "VALUES ($holder, $it, 'LONG', '2027-01-15T00:00:00Z', 5000000)",
            )
        }
        clock.now = Instant.parse("2027-03-21T03:00:00Z")
        send(IngestPayload.BoutResult("kb", "WIN", ref("k1"), "KO_TKO", 1))
        assertEquals("0", fx.query("SELECT count(*) FROM review_item").single())
        assertEquals(1, dividends(holder).size)
        assertEquals(fx.ledgerSum(holder), fx.cashCached(holder))
    }
}
