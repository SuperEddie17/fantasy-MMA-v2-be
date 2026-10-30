package com.fightexchange.trading

import com.fightexchange.PostgresTest
import com.fightexchange.common.ApiException
import com.fightexchange.market.MutableClock
import io.micronaut.http.HttpStatus
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@MicronautTest(transactional = false)
class TradingServiceTest :
    PostgresTest(),
    TestPropertyProvider {
    @Inject lateinit var tx: TransactionOperations<Connection>

    @Inject lateinit var trading: TradingService

    @Inject lateinit var clock: MutableClock

    override fun getProperties(): Map<String, String> = super.getProperties() + ("test.clock" to "mutable")

    private lateinit var fx: TradingFixture
    private var portfolio = 0L
    private var seasonId = 0L
    private var userId = 0L

    @BeforeEach
    fun setUp() {
        fx = TradingFixture(tx)
        fx.clean()
        // Wednesday, well after the season start (so not the first week).
        clock.now = Instant.parse("2025-01-01T12:00:00Z")
        userId = fx.user()
        seasonId = fx.season()
        portfolio = fx.portfolio(userId, seasonId)
    }

    @AfterEach
    fun tearDown() = fx.clean()

    private fun buy(
        fighter: Long,
        key: String? = null,
    ) = trading.trade(portfolio, TradeRequest("BUY", fighter, key))

    private fun refused(block: () -> Unit): String {
        val e = assertThrows(ApiException::class.java, block)
        return e.code
    }

    private fun assertLedgerMatches() {
        assertEquals(fx.cashCached(portfolio), fx.ledgerSum(portfolio))
    }

    @Test
    fun `buy costs price plus fee, opens a lot and is booked in the ledger`() {
        val f = fx.fighter("A")
        val result = buy(f)
        assertEquals(6_000_000, result.trade.price)
        assertEquals(60_000, result.trade.fee)
        assertEquals(0, result.trade.extraFee)
        assertTrue(result.trade.freeBuy)
        assertEquals(100_000_000 - 6_060_000, result.cash)
        assertEquals(result.cash, fx.cashCached(portfolio))
        assertEquals(
            listOf("TRADE -6000000", "FEE -60000"),
            fx.query(
                "SELECT kind || ' ' || amount FROM ledger_entry WHERE trade_id = ${result.trade.id} ORDER BY id",
            ),
        )
        assertEquals(
            listOf("LONG 6000000"),
            fx.query(
                "SELECT side || ' ' || open_price FROM position_lot WHERE portfolio_id = $portfolio AND closed_at IS NULL",
            ),
        )
        assertLedgerMatches()
    }

    @Test
    fun `the third buy of the week pays the extra fee and Monday resets it`() {
        val f = (1..4).map { fx.fighter("F$it") }
        assertEquals(0, buy(f[0]).trade.extraFee)
        assertEquals(0, buy(f[1]).trade.extraFee)
        val third = buy(f[2])
        assertEquals(180_000, third.trade.extraFee)
        assertFalse(third.trade.freeBuy)
        assertEquals(
            listOf("EXTRA_FEE -180000"),
            fx.query(
                "SELECT kind || ' ' || amount FROM ledger_entry WHERE trade_id = ${third.trade.id} AND kind = 'EXTRA_FEE'",
            ),
        )
        // next Monday 00:00 UTC
        clock.now = Instant.parse("2025-01-06T00:00:00Z")
        assertEquals(0, buy(f[3]).trade.extraFee)
        assertLedgerMatches()
    }

    @Test
    fun `the first week of the season has no extra fee`() {
        val first = fx.season("2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        portfolio = fx.portfolio(fx.user("bob@example.com"), first)
        repeat(4) { assertEquals(0, buy(fx.fighter("W$it")).trade.extraFee) }
    }

    @Test
    fun `sell returns the price less the fee and closes the oldest lot`() {
        val f = fx.fighter("A")
        buy(f)
        fx.exec("UPDATE market_listing SET price = 7000000 WHERE fighter_id = $f")
        val sold = trading.trade(portfolio, TradeRequest("SELL", f))
        assertEquals(7_000_000 - 70_000, sold.cash - (100_000_000 - 6_060_000))
        assertEquals(
            listOf("7000000"),
            fx.query("SELECT close_price FROM position_lot WHERE portfolio_id = $portfolio AND closed_at IS NOT NULL"),
        )
        assertEquals("NOT_HELD", refused { trading.trade(portfolio, TradeRequest("SELL", f)) })
        assertLedgerMatches()
    }

    @Test
    fun `a short pays out the price on opening and costs price plus fee on closing`() {
        val f = fx.fighter("A")
        val opened = trading.trade(portfolio, TradeRequest("SHORT_OPEN", f))
        assertEquals(100_000_000 + 6_000_000 - 60_000, opened.cash)
        assertEquals("LONG_AND_SHORT_SAME_FIGHTER", refused { buy(f) })
        val closed = trading.trade(portfolio, TradeRequest("SHORT_CLOSE", f))
        assertEquals(opened.cash - 6_060_000, closed.cash)
        assertEquals(0, fx.query("SELECT 1 FROM weekly_buy_counter WHERE portfolio_id = $portfolio").size)
        assertLedgerMatches()
    }

    @Test
    fun `at most three shorts and shorts on announced favourites are refused`() {
        val f = (1..4).map { fx.fighter("S$it") }
        f.take(3).forEach { trading.trade(portfolio, TradeRequest("SHORT_OPEN", it)) }
        assertEquals("TOO_MANY_SHORTS", refused { trading.trade(portfolio, TradeRequest("SHORT_OPEN", f[3])) })
        val favourite = fx.fighter("Fav")
        val other = fx.fighter("Dog")
        val event = fx.event(Instant.parse("2025-02-01T00:00:00Z"), null)
        val bout = fx.bout(event, favourite, other)
        fx.exec(
            "INSERT INTO odds_snapshot (bout_id, source, taken_at, odds_a, odds_b) VALUES ($bout, 'T', now(), 1.1, 4.0)",
        )
        fx.exec("DELETE FROM position_lot WHERE portfolio_id = $portfolio")
        assertEquals("SHORT_ODDS_TOO_HIGH", refused { trading.trade(portfolio, TradeRequest("SHORT_OPEN", other)) })
        trading.trade(portfolio, TradeRequest("SHORT_OPEN", favourite))
    }

    @Test
    fun `at most 15 longs, two spots stay for non-UFC fighters`() {
        val ufc = (1..13).map { fx.fighter("U$it", 1_000_000) }
        val okt = (1..3).map { fx.fighter("O$it", 1_000_000, "OKT") }
        ufc.forEach { buy(it) }
        assertEquals("MIN_NON_UFC", refused { buy(fx.fighter("U14", 1_000_000)) })
        buy(okt[0])
        buy(okt[1])
        assertEquals("ROSTER_FULL", refused { buy(okt[2]) })
        // selling a non-UFC fighter would break the minimum of two
        assertEquals("MIN_NON_UFC", refused { trading.trade(portfolio, TradeRequest("SELL", okt[0])) })
        assertEquals("ALREADY_HELD", refused { buy(ufc[0]) })
        assertLedgerMatches()
    }

    @Test
    fun `both opponents of an announced bout cannot be held`() {
        val a = fx.fighter("A")
        val b = fx.fighter("B")
        fx.bout(fx.event(Instant.parse("2025-02-01T00:00:00Z"), null), a, b)
        buy(a)
        assertEquals("BOTH_OPPONENTS", refused { buy(b) })
    }

    @Test
    fun `trading closes at the weigh-in and reopens once the result is confirmed`() {
        val a = fx.fighter("A")
        val b = fx.fighter("B")
        val event = fx.event(Instant.parse("2025-01-03T00:00:00Z"), Instant.parse("2025-01-02T12:00:00Z"))
        val bout = fx.bout(event, a, b)
        buy(a)
        clock.now = Instant.parse("2025-01-02T12:00:00Z")
        assertEquals("TRADING_LOCKED", refused { buy(b) })
        assertEquals("TRADING_LOCKED", refused { trading.trade(portfolio, TradeRequest("SELL", a)) })
        val quote = trading.quote(portfolio, TradeRequest("BUY", b))
        assertFalse(quote.allowed)
        assertTrue(quote.violations.any { it.code == "TRADING_LOCKED" })
        fx.exec("UPDATE bout SET status = 'COMPLETED' WHERE id = $bout")
        trading.trade(portfolio, TradeRequest("SELL", a))
        buy(b)
    }

    @Test
    fun `a bout without weigh-in time locks from the event start, a later one does not`() {
        val a = fx.fighter("A")
        val b = fx.fighter("B")
        fx.bout(fx.event(Instant.parse("2025-01-02T00:00:00Z"), null), a, b)
        buy(a)
        clock.now = Instant.parse("2025-01-02T00:00:00Z")
        assertEquals("TRADING_LOCKED", refused { trading.trade(portfolio, TradeRequest("SELL", a)) })
    }

    @Test
    fun `delisted fighters and unknown fighters cannot be traded`() {
        val gone = fx.fighter("Gone", status = "DELISTED")
        assertEquals("FIGHTER_NOT_TRADABLE", refused { buy(gone) })
        assertEquals("FIGHTER_NOT_FOUND", refused { buy(gone + 1000) })
    }

    @Test
    fun `cash never goes negative and a refused trade changes nothing`() {
        val rich = fx.fighter("Rich", 100_000_000)
        fx.exec("UPDATE portfolio SET cash_cached = 50000000")
        fx.exec("UPDATE ledger_entry SET amount = 50000000")
        assertEquals("INSUFFICIENT_CASH", refused { buy(rich) })
        assertEquals(0, fx.query("SELECT 1 FROM trade").size)
        assertEquals(50_000_000, fx.cashCached(portfolio))
        val q = trading.quote(portfolio, TradeRequest("BUY", rich))
        assertFalse(q.allowed)
        assertEquals(-101_000_000, q.cashChange)
    }

    @Test
    fun `the quote shows the price, extra fee and remaining free buys without trading`() {
        val f = (1..3).map { fx.fighter("Q$it") }
        buy(f[0])
        buy(f[1])
        val q = trading.quote(portfolio, TradeRequest("BUY", f[2]))
        assertTrue(q.allowed)
        assertEquals(2, q.buysThisWeek)
        assertEquals(0, q.freeBuysLeft)
        assertEquals(180_000, q.extraFee)
        assertEquals(-6_240_000, q.cashChange)
        assertEquals(2, fx.query("SELECT 1 FROM trade").size)
    }

    @Test
    fun `a repeated idempotency key returns the stored trade and charges once`() {
        val f = fx.fighter("A")
        val first = buy(f, "k1")
        val second = buy(f, "k1")
        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(first.trade.id, second.trade.id)
        assertEquals(first.cash, second.cash)
        assertEquals(1, fx.query("SELECT 1 FROM trade").size)
        assertEquals("IDEMPOTENCY_KEY_REUSED", refused { trading.trade(portfolio, TradeRequest("SELL", f, "k1")) })
        assertLedgerMatches()
    }

    @Test
    fun `bad requests are validation errors and trading outside the season is refused`() {
        val f = fx.fighter("A")
        assertEquals("VALIDATION_ERROR", refused { trading.trade(portfolio, TradeRequest("GIFT", f)) })
        assertEquals("VALIDATION_ERROR", refused { trading.trade(portfolio, TradeRequest("BUY", null)) })
        assertEquals("VALIDATION_ERROR", refused { trading.trade(portfolio, TradeRequest("BUY", f, "x".repeat(101))) })
        assertEquals("PORTFOLIO_NOT_FOUND", refused { trading.trade(portfolio + 999, TradeRequest("BUY", f)) })
        clock.now = Instant.parse("2026-02-01T00:00:00Z")
        assertEquals("SEASON_ENDED", refused { buy(f) })
        clock.now = Instant.parse("2024-06-01T00:00:00Z")
        assertEquals("SEASON_NOT_STARTED", refused { buy(f) })
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ApiException::class.java) { buy(f) }.status)
    }

    @Test
    fun `concurrent buys on one portfolio never spend the same cash twice`() {
        val fighters = (1..10).map { fx.fighter("C$it") }
        fx.exec("UPDATE portfolio SET cash_cached = 20000000")
        fx.exec("UPDATE ledger_entry SET amount = 20000000")
        val pool = Executors.newFixedThreadPool(10)
        val results =
            fighters
                .map { f -> pool.submit<Boolean> { runCatching { buy(f) }.isSuccess } }
                .map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        // 6.06 + 6.06 + 6.24 = 18.36 M fits into 20 M, a fourth buy (6.24 M) does not.
        assertEquals(3, results.count { it })
        assertEquals(3, fx.query("SELECT 1 FROM trade").size)
        assertTrue(fx.cashCached(portfolio) >= 0)
        assertLedgerMatches()
    }
}
