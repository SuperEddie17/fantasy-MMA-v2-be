package com.fightexchange.market

import com.fightexchange.PostgresTest
import com.fightexchange.trading.TradingFixture
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant

@MicronautTest(transactional = false)
class DemandJobTest :
    PostgresTest(),
    TestPropertyProvider {
    @Inject lateinit var tx: TransactionOperations<Connection>

    @Inject lateinit var job: DemandJob

    @Inject lateinit var clock: MutableClock

    override fun getProperties(): Map<String, String> = super.getProperties() + ("test.clock" to "mutable")

    private lateinit var fx: TradingFixture
    private var portfolios = emptyList<Long>()

    @BeforeEach
    fun setUp() {
        fx = TradingFixture(tx)
        fx.clean()
        clock.now = Instant.parse("2025-03-10T12:00:00Z")
        val season = fx.season()
        portfolios = (1..4).map { fx.portfolio(fx.user("p$it@example.com"), season) }
    }

    @AfterEach
    fun tearDown() = fx.clean()

    private fun trade(
        portfolio: Int,
        fighter: Long,
        kind: String,
        at: String,
    ) = fx.exec(
        "INSERT INTO trade (portfolio_id, fighter_id, kind, price, at) " +
            "VALUES (${portfolios[portfolio]}, $fighter, '$kind', 6000000, '$at')",
    )

    private fun demand(fighter: Long) =
        fx.query("SELECT demand_factor || ' ' || price FROM market_listing WHERE fighter_id = $fighter").single()

    @Test
    fun `net buys raise the factor, net sells lower it, the rest stays`() {
        val hot = fx.fighter("Hot")
        val cold = fx.fighter("Cold")
        val calm = fx.fighter("Calm")
        // hot: 2 buys + 1 short close - 0 = +3 of 4 portfolios -> +37.5 % -> capped at +30 %
        trade(0, hot, "BUY", "2025-03-09T10:00:00Z")
        trade(1, hot, "BUY", "2025-03-08T10:00:00Z")
        trade(2, hot, "SHORT_CLOSE", "2025-03-05T10:00:00Z")
        // cold: 1 sell - 0 = -1 of 4 -> -12.5 %
        trade(0, cold, "SELL", "2025-03-09T10:00:00Z")
        // calm: buy and sell cancel out
        trade(0, calm, "BUY", "2025-03-09T10:00:00Z")
        trade(1, calm, "SELL", "2025-03-09T11:00:00Z")
        assertEquals(2, job.run())
        assertEquals("1.300000 7800000", demand(hot))
        assertEquals("0.875000 5250000", demand(cold))
        assertEquals("1.000000 6000000", demand(calm))
        assertEquals(
            listOf("DEMAND 7800000", "DEMAND 5250000"),
            fx.query("SELECT kind || ' ' || price_after FROM price_event ORDER BY id"),
        )
    }

    @Test
    fun `a second run changes nothing and trades older than a week stop counting`() {
        val f = fx.fighter("Hot")
        trade(0, f, "BUY", "2025-03-09T10:00:00Z")
        assertEquals(1, job.run())
        assertEquals(0, job.run())
        assertEquals(1, fx.query("SELECT 1 FROM price_event").size)
        clock.now = Instant.parse("2025-03-17T00:00:00Z")
        assertEquals(1, job.run())
        assertEquals("1.000000 6000000", demand(f))
        assertEquals(2, fx.query("SELECT 1 FROM price_event").size)
    }

    @Test
    fun `forced sells, delisted fighters and a world without portfolios are ignored`() {
        val f = fx.fighter("Forced")
        val gone = fx.fighter("Gone", status = "DELISTED")
        trade(0, f, "FORCED_SELL", "2025-03-09T10:00:00Z")
        trade(0, gone, "BUY", "2025-03-09T10:00:00Z")
        assertEquals(0, job.run())
        assertEquals("1.000000 6000000", demand(f))
        fx.exec("DELETE FROM trade; DELETE FROM ledger_entry; DELETE FROM portfolio")
        assertEquals(0, job.run())
    }
}
