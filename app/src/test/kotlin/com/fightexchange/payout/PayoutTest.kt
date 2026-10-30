package com.fightexchange.payout

import com.fightexchange.PostgresTest
import com.fightexchange.ingest.ADMIN_SOURCE
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
import com.fightexchange.market.MutableClock
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

private const val START = 100_000_000L

/** Dividends, short settlement and their reversal, driven through the real `bout.result` ingest. */
@MicronautTest(transactional = false)
class PayoutTest :
    PostgresTest(),
    TestPropertyProvider {
    @Inject lateinit var tx: TransactionOperations<Connection>

    @Inject lateinit var service: IngestService

    @Inject lateinit var processor: IngestProcessor

    @Inject lateinit var payouts: PayoutService

    @Inject lateinit var clock: MutableClock

    override fun getProperties(): Map<String, String> = super.getProperties() + ("test.clock" to "mutable")

    private lateinit var fx: TradingFixture
    private var seq = 0
    private var season = 0L
    private var users = 0
    private lateinit var fillers: List<Long>

    @BeforeEach
    fun setUp() {
        fx = TradingFixture(tx)
        fx.cleanAll()
        clock.now = Instant.parse("2026-12-01T12:00:00Z")
        season = fx.season("2026-01-01T00:00:00Z", "2027-06-01T00:00:00Z")
        fillers = (1..7).map { fx.fighter("Filler $it") }
    }

    @AfterEach
    fun tearDown() = fx.cleanAll()

    private fun send(payload: IngestPayload) {
        service.submit(ADMIN_SOURCE, "k${seq++}", payload)
        processor.processPending()
    }

    private fun ref(id: String) = IngestPayload.FighterRef(externalId = id)

    /** Two fighters on one announced main event bout with closing odds [oddsA] / [oddsB]. */
    private fun card(odds: Pair<Double, Double>? = 1.5 to 2.6) {
        send(IngestPayload.FighterUpsert("f1", "Jiří Procházka", "M", "UFC", "Light Heavyweight"))
        send(IngestPayload.FighterUpsert("f2", "Khalil Rountree", "M", "UFC", "Light Heavyweight"))
        send(IngestPayload.EventUpsert("e1", "UFC", "UFC 999", Instant.parse("2026-12-05T18:00:00Z")))
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2"), "MAIN"))
        if (odds != null) {
            send(IngestPayload.OddsSnapshot("b1", Instant.parse("2026-12-05T12:00:00Z"), odds.first, odds.second, true))
        }
        clock.now = Instant.parse("2026-12-06T02:00:00Z")
    }

    private fun result(winner: String) =
        IngestPayload.BoutResult(
            boutExternalId = "b1",
            outcome = "WIN",
            winner = ref(winner),
            method = "KO_TKO",
            round = 2,
            bonuses = listOf(IngestPayload.Bonus(ref(winner), "PERFORMANCE")),
        )

    private fun fighter(external: String) =
        fx.query("SELECT fighter_id FROM fighter_external_id WHERE external_id = '$external'").single().toLong()

    private fun price(fighter: Long) =
        fx
            .query(
                "SELECT price FROM market_listing WHERE fighter_id = $fighter",
            ).single()
            .toLong()

    private fun lot(
        portfolio: Long,
        fighter: Long,
        side: String = "LONG",
        openedAt: String = "2026-11-01T00:00:00Z",
    ) = fx.exec(
        "INSERT INTO position_lot (portfolio_id, fighter_id, side, opened_at, open_price) " +
            "VALUES ($portfolio, $fighter, '$side', '$openedAt', 5000000)",
    )

    /** A portfolio holding [fighters] long, plus [shorts]; [fill] adds filler fighters up to 8 longs. */
    private fun player(
        longs: List<Long>,
        shorts: List<Long> = emptyList(),
        fill: Boolean = true,
        longsOpenedAt: String = "2026-11-01T00:00:00Z",
    ): Long {
        val p = fx.portfolio(fx.user("p${users++}@example.com"), season, START)
        longs.forEach { lot(p, it, openedAt = longsOpenedAt) }
        shorts.forEach { lot(p, it, "SHORT") }
        if (fill) fillers.take(8 - longs.size).forEach { lot(p, it) }
        return p
    }

    private fun amounts(portfolio: Long) =
        fx
            .query(
                "SELECT amount FROM dividend_payout WHERE portfolio_id = $portfolio AND reversed_at IS NULL ORDER BY id",
            ).map { it.toLong() }

    private fun assertLedgerAgrees(vararg portfolios: Long) =
        portfolios.forEach { assertEquals(fx.ledgerSum(it), fx.cashCached(it), "portfolio $it") }

    @Test
    fun `winner long with captain, losers and shorts are settled and the ledger agrees`() {
        card()
        val a = fighter("f1")
        val b = fighter("f2")
        val winnerHolder = player(listOf(a))
        val loserHolder = player(listOf(b))
        val shortWinner = player(emptyList(), listOf(a), fill = false)
        val shortLoser = player(emptyList(), listOf(b), fill = false)
        fx.exec(
            "INSERT INTO captain_pick (portfolio_id, event_id, fighter_id, picked_at) SELECT $winnerHolder, id, $a, now() FROM event",
        )
        val p = price(a)

        send(result("f1"))

        // (price x 0.25 x 1.5 base x 1.5 odds / 1.2 + 1 M bonus of the night) x 2 for the captain
        val expected = (p * 0.25 * 1.5 * 1.5 / 1.2 + 1_000_000) * 2
        assertEquals(expected, amounts(winnerHolder).single().toDouble(), 1.0)
        assertEquals(listOf(0L), amounts(loserHolder))
        assertEquals(listOf(-1_500_000L), amounts(shortWinner))
        assertEquals(listOf(1_500_000L), amounts(shortLoser))
        assertEquals(START - 1_500_000, fx.cashCached(shortWinner))
        assertEquals(START + 1_500_000, fx.cashCached(shortLoser))
        assertEquals(START, fx.cashCached(loserHolder))
        assertEquals(
            "DIVIDEND",
            fx
                .query(
                    "SELECT kind FROM ledger_entry WHERE portfolio_id = $winnerHolder AND bout_id IS NOT NULL",
                ).single(),
        )
        assertEquals(
            "SHORT_SETTLE",
            fx.query("SELECT kind FROM ledger_entry WHERE portfolio_id = $shortLoser AND bout_id IS NOT NULL").single(),
        )
        assertEquals(
            p.toString(),
            fx.query("SELECT price FROM dividend_payout WHERE portfolio_id = $winnerHolder").single(),
        )
        assertEquals(
            "t",
            fx.query("SELECT captain FROM dividend_payout WHERE portfolio_id = $winnerHolder").single(),
        )
        assertLedgerAgrees(winnerHolder, loserHolder, shortWinner, shortLoser)
    }

    @Test
    fun `only lots bought before the announcement and full rosters earn a long dividend`() {
        card()
        val a = fighter("f1")
        val late = player(listOf(a), longsOpenedAt = "2026-12-02T00:00:00Z")
        val small = player(listOf(a), fill = false)
        val ok = player(listOf(a))

        send(result("f1"))

        assertEquals(emptyList<Long>(), amounts(late))
        assertEquals(emptyList<Long>(), amounts(small))
        assertEquals(1, amounts(ok).size)
        assertEquals(START, fx.cashCached(late))
        assertEquals(START, fx.cashCached(small))
    }

    @Test
    fun `a draw and a no contest pay nothing`() {
        card()
        val a = fighter("f1")
        val holder = player(listOf(a))
        val shorter = player(emptyList(), listOf(a), fill = false)

        send(IngestPayload.BoutResult(boutExternalId = "b1", outcome = "DRAW", method = "DRAW"))

        assertEquals(listOf(0L), amounts(holder))
        assertEquals(listOf(0L), amounts(shorter))
        assertEquals(0, fx.query("SELECT 1 FROM ledger_entry WHERE bout_id IS NOT NULL").size)
        assertEquals(START, fx.cashCached(holder))
    }

    @Test
    fun `without odds the Elo implied odds are used`() {
        card(odds = null)
        val a = fighter("f1")
        val holder = player(listOf(a))

        send(result("f1"))

        assertEquals(
            2.0,
            fx.query("SELECT odds_used FROM dividend_payout WHERE portfolio_id = $holder").single().toDouble(),
        )
    }

    @Test
    fun `settling the same result twice pays once`() {
        card()
        val a = fighter("f1")
        val holder = player(listOf(a))
        send(result("f1"))
        val cash = fx.cashCached(holder)
        val boutId = fx.query("SELECT id FROM bout").single().toLong()

        val rows = tx.executeWrite { payouts.settle(it.connection, boutId, clock.instant()) }

        assertEquals(0, rows)
        assertEquals(cash, fx.cashCached(holder))
        assertEquals(1, fx.query("SELECT 1 FROM dividend_payout WHERE portfolio_id = $holder").size)
    }

    @Test
    fun `a corrected result reverses the old payouts and pays the new winner at the same price`() {
        card()
        val a = fighter("f1")
        val b = fighter("f2")
        val aHolder = player(listOf(a))
        val bHolder = player(listOf(b))
        val shortA = player(emptyList(), listOf(a), fill = false)
        val shortB = player(emptyList(), listOf(b), fill = false)
        val priceB = price(b)

        send(result("f1"))
        send(result("f2"))

        assertEquals(START, fx.cashCached(aHolder))
        assertEquals(2, fx.query("SELECT 1 FROM dividend_payout WHERE portfolio_id = $aHolder").size)
        assertEquals(listOf(0L), amounts(aHolder))
        assertEquals(
            2,
            fx
                .query(
                    "SELECT result_version FROM dividend_payout WHERE portfolio_id = $aHolder AND reversed_at IS NULL",
                ).single()
                .toInt(),
        )
        val expectedB = priceB * 0.25 * 1.5 * 2.6 / 1.2 + 1_000_000
        assertEquals(expectedB, amounts(bHolder).single().toDouble(), 1.0)
        assertEquals(
            priceB.toString(),
            fx
                .query(
                    "SELECT price FROM dividend_payout WHERE portfolio_id = $bHolder AND reversed_at IS NULL",
                ).single(),
        )
        // short on the old winner: paid 1.5 M, reversed, now receives 1 M x the old winner's... the new loser's opponent odds 2.6
        assertEquals(START + 2_600_000, fx.cashCached(shortA))
        assertEquals(START - 2_600_000, fx.cashCached(shortB))
        assertEquals(
            1,
            fx
                .query(
                    "SELECT 1 FROM ledger_entry WHERE portfolio_id = $shortA AND kind = 'REVERSAL' AND reverses_id IS NOT NULL",
                ).size,
        )
        assertLedgerAgrees(aHolder, bHolder, shortA, shortB)
    }
}
