package com.fightexchange.trading

import com.fightexchange.PostgresTest
import com.fightexchange.admin.DelistOutcome
import com.fightexchange.admin.MarketAdmin
import com.fightexchange.market.MarketJobs
import com.fightexchange.market.MutableClock
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@MicronautTest(transactional = false)
class DelistPayoutTest :
    PostgresTest(),
    TestPropertyProvider {
    @Inject lateinit var tx: TransactionOperations<Connection>

    @Inject lateinit var admin: MarketAdmin

    @Inject lateinit var jobs: MarketJobs

    @Inject lateinit var trading: TradingService

    @Inject lateinit var check: LedgerCheck

    @Inject lateinit var clock: MutableClock

    override fun getProperties(): Map<String, String> = super.getProperties() + ("test.clock" to "mutable")

    private lateinit var fx: TradingFixture
    private var season = 0L

    @BeforeEach
    fun setUp() {
        fx = TradingFixture(tx)
        fx.clean()
        clock.now = Instant.parse("2025-03-10T12:00:00Z")
        season = fx.season()
    }

    @AfterEach
    fun tearDown() = fx.clean()

    private fun hold(
        portfolio: Long,
        fighter: Long,
        side: String,
        price: Long,
    ) {
        fx.exec(
            "INSERT INTO position_lot (portfolio_id, fighter_id, side, opened_at, open_price) " +
                "VALUES ($portfolio, $fighter, '$side', '2025-01-01T00:00:00Z', $price)",
        )
    }

    @Test
    fun `an admin delisting pays out longs and charges shorts at the last price without fees`() {
        val f = fx.fighter("Leaving", 7_000_000)
        val longPlayer = fx.portfolio(fx.user("long@example.com"), season, 10_000_000)
        val shortPlayer = fx.portfolio(fx.user("short@example.com"), season, 3_000_000)
        val bystander = fx.portfolio(fx.user("other@example.com"), season, 5_000_000)
        hold(longPlayer, f, "LONG", 6_000_000)
        hold(shortPlayer, f, "SHORT", 6_500_000)
        assertEquals(DelistOutcome.Delisted, admin.delist(f, "retired"))

        assertEquals(17_000_000, fx.cashCached(longPlayer))
        assertEquals(-4_000_000, fx.cashCached(shortPlayer))
        assertEquals(5_000_000, fx.cashCached(bystander))
        assertEquals(0, fx.query("SELECT 1 FROM position_lot WHERE closed_at IS NULL").size)
        assertEquals(
            listOf("FORCED_SELL 7000000 0", "FORCED_SELL 7000000 0"),
            fx.query("SELECT kind || ' ' || price || ' ' || fee FROM trade ORDER BY id"),
        )
        assertEquals(
            listOf("DELIST_PAYOUT 7000000", "DELIST_PAYOUT -7000000"),
            fx.query("SELECT kind || ' ' || amount FROM ledger_entry WHERE kind = 'DELIST_PAYOUT' ORDER BY id"),
        )
        assertEquals(listOf("7000000"), fx.query("SELECT DISTINCT close_price FROM position_lot"))
        listOf(longPlayer, shortPlayer, bystander).forEach { assertEquals(fx.cashCached(it), fx.ledgerSum(it)) }
        assertEquals(emptyList<LedgerMismatch>(), check.run())
    }

    @Test
    fun `delisting after 78 weeks without a fight pays out as well`() {
        val f = fx.fighter("Forgotten", 4_000_000)
        fx.exec("UPDATE market_listing SET listed_at = '2022-01-01T00:00:00Z' WHERE fighter_id = $f")
        val p = fx.portfolio(fx.user(), season, 1_000_000)
        hold(p, f, "LONG", 4_000_000)
        assertEquals(1, jobs.runInactivity(clock.now))
        assertEquals(5_000_000, fx.cashCached(p))
        assertEquals(fx.cashCached(p), fx.ledgerSum(p))
    }

    @Test
    fun `a delisted fighter cannot be traded any more`() {
        val f = fx.fighter("Leaving")
        val p = fx.portfolio(fx.user(), season)
        admin.delist(f, null)
        val e =
            org.junit.jupiter.api.Assertions.assertThrows(com.fightexchange.common.ApiException::class.java) {
                trading.trade(p, TradeRequest("BUY", f))
            }
        assertEquals("FIGHTER_NOT_TRADABLE", e.code)
    }

    @Test
    fun `the ledger check reports a portfolio whose cash does not match`() {
        val p = fx.portfolio(fx.user(), season, 1_000_000)
        assertEquals(emptyList<LedgerMismatch>(), check.run())
        fx.exec("UPDATE portfolio SET cash_cached = cash_cached + 1 WHERE id = $p")
        assertEquals(listOf(LedgerMismatch(p, 1_000_001, 1_000_000)), check.run())
    }

    @Test
    fun `buys racing a delisting leave no open position and a consistent ledger`() {
        val f = fx.fighter("Racing", 2_000_000)
        val portfolios = (1..8).map { fx.portfolio(fx.user("r$it@example.com"), season) }
        val pool = Executors.newFixedThreadPool(9)
        val buys =
            portfolios.map { p ->
                pool.submit<Boolean> { runCatching { trading.trade(p, TradeRequest("BUY", f)) }.isSuccess }
            }
        val delist = pool.submit<DelistOutcome> { admin.delist(f, "race") }
        buys.forEach { it.get(30, TimeUnit.SECONDS) }
        assertEquals(DelistOutcome.Delisted, delist.get(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, fx.query("SELECT 1 FROM position_lot WHERE closed_at IS NULL").size)
        portfolios.forEach { assertEquals(fx.cashCached(it), fx.ledgerSum(it)) }
        // a buyer that got in before the delisting is paid out the price it paid: only fees are lost
        portfolios.forEach { assertTrue(fx.cashCached(it) in 99_900_000..100_000_000) }
    }
}
