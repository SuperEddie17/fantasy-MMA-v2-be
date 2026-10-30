package com.fightexchange.ranking

import com.fightexchange.PostgresTest
import com.fightexchange.payout.CaptainService
import com.fightexchange.trading.TradingFixture
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

private const val BUDGET = 100_000_000L

@MicronautTest(transactional = false)
class RankingJobsTest : PostgresTest() {
    @Inject lateinit var tx: TransactionOperations<Connection>

    @Inject lateinit var jobs: RankingJobs

    @Inject lateinit var leaderboards: LeaderboardService

    @Inject lateinit var netWorth: NetWorthService

    @Inject lateinit var captains: CaptainService

    private lateinit var fx: TradingFixture
    private var season = 0L
    private var users = 0

    @BeforeEach
    fun setUp() {
        fx = TradingFixture(tx)
        fx.cleanAll()
        season = fx.season("2026-11-01T00:00:00Z", "2027-06-01T00:00:00Z")
    }

    @AfterEach
    fun tearDown() = fx.cleanAll()

    private fun portfolio(cash: Long = BUDGET) = fx.portfolio(fx.user("p${users++}@example.com"), season, cash)

    private fun snapshot(
        portfolio: Long,
        day: String,
        netWorth: Long,
    ) = fx.exec(
        "INSERT INTO net_worth_snapshot VALUES ($portfolio, '$day', $netWorth, 0, 0, $netWorth)",
    )

    private fun board(period: String) =
        fx
            .query(
                "SELECT portfolio_id || ':' || rank || ':' || net_worth || ':' || change FROM leaderboard_entry " +
                    "WHERE season_id = $season AND period = '$period' ORDER BY rank, portfolio_id",
            )

    @Test
    fun `net worth job snapshots cash plus longs minus shorts and is idempotent`() {
        val longFighter = fx.fighter("Long", 6_000_000)
        val shortFighter = fx.fighter("Short", 2_000_000)
        val p = portfolio(90_000_000)
        fx.exec(
            "INSERT INTO position_lot (portfolio_id, fighter_id, side, opened_at, open_price) VALUES " +
                "($p, $longFighter, 'LONG', '2026-11-02T00:00:00Z', 5000000), " +
                "($p, $shortFighter, 'SHORT', '2026-11-02T00:00:00Z', 2500000)",
        )
        val empty = portfolio()
        val old = fx.season("2025-01-01T00:00:00Z", "2025-06-01T00:00:00Z")
        fx.portfolio(fx.user("old@example.com"), old)

        val now = Instant.parse("2026-12-10T00:15:00Z")
        assertEquals(2, jobs.runNetWorth(now))
        assertEquals(2, jobs.runNetWorth(now))

        assertEquals(
            listOf("2026-12-10:90000000:6000000:2000000:94000000"),
            fx.query(
                "SELECT day || ':' || cash || ':' || long_value || ':' || short_value || ':' || net_worth " +
                    "FROM net_worth_snapshot WHERE portfolio_id = $p",
            ),
        )
        assertEquals(
            listOf(BUDGET.toString()),
            fx.query("SELECT net_worth FROM net_worth_snapshot WHERE portfolio_id = $empty"),
        )
        assertEquals(2, fx.query("SELECT 1 FROM net_worth_snapshot").size)
        val history = netWorth.history(p, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31))
        assertEquals(94_000_000, history.single().netWorth)
    }

    @Test
    fun `season ranking orders by net worth, ties share a rank, and the month sprint ranks the change`() {
        val a = portfolio()
        val b = portfolio()
        val c = portfolio()
        val late = portfolio() // joined during December: no snapshot on the first day
        snapshot(a, "2026-12-01", 100_000_000)
        snapshot(b, "2026-12-01", 120_000_000)
        snapshot(c, "2026-12-01", 100_000_000)
        snapshot(a, "2026-12-15", 110_000_000)
        snapshot(b, "2026-12-15", 125_000_000)
        snapshot(c, "2026-12-15", 110_000_000)
        snapshot(late, "2026-12-15", 108_000_000)

        jobs.runLeaderboard(Instant.parse("2026-12-15T00:20:00Z"))

        assertEquals(
            listOf(
                "$b:1:125000000:25000000",
                "$a:2:110000000:10000000",
                "$c:2:110000000:10000000",
                "$late:4:108000000:8000000",
            ),
            board("SEASON"),
        )
        // sprint: a and c +10 M, b +5 M, late +8 M against its starting budget
        assertEquals(
            listOf(
                "$a:1:110000000:10000000",
                "$c:1:110000000:10000000",
                "$late:3:108000000:8000000",
                "$b:4:125000000:5000000",
            ),
            board("MONTH-2026-12"),
        )
        val page = leaderboards.read(season, "MONTH-2026-12", 0, 2, Instant.parse("2026-12-15T01:00:00Z"))
        assertEquals(2, page.items.size)
        assertEquals(2, page.nextOffset)
        assertEquals(false, page.closed)
    }

    @Test
    fun `month close stores the final sprint and later rebuilds leave it alone`() {
        val a = portfolio()
        val b = portfolio()
        snapshot(a, "2026-12-01", 100_000_000)
        snapshot(b, "2026-12-01", 100_000_000)
        snapshot(a, "2026-12-31", 90_000_000)
        snapshot(b, "2026-12-31", 120_000_000)
        // value at the start of January, i.e. the end of December
        snapshot(a, "2027-01-01", 95_000_000)
        snapshot(b, "2027-01-01", 130_000_000)
        snapshot(a, "2027-01-20", 50_000_000)

        assertEquals(2, jobs.runMonthClose(Instant.parse("2027-01-01T01:00:00Z")))

        assertEquals(listOf("$b:1:130000000:30000000", "$a:2:95000000:-5000000"), board("MONTH-2026-12"))
        assertEquals("t", fx.query("SELECT DISTINCT closed FROM leaderboard_entry").single())
        val rebuilt =
            tx.executeWrite {
                leaderboards.rebuildMonth(
                    it.connection,
                    season,
                    YearMonth.of(2026, 12),
                    LocalDate.of(2027, 1, 20),
                    false,
                )
            }
        assertEquals(0, rebuilt)
        assertEquals(listOf("$b:1:130000000:30000000", "$a:2:95000000:-5000000"), board("MONTH-2026-12"))
    }

    @Test
    fun `captain lock job locks the picks of events past their weigh-in`() {
        val p = portfolio()
        val f = fx.fighter("F")
        val past = fx.event(Instant.parse("2026-12-05T18:00:00Z"), Instant.parse("2026-12-04T18:00:00Z"))
        val future = fx.event(Instant.parse("2026-12-20T18:00:00Z"), null)
        fx.exec(
            "INSERT INTO captain_pick (portfolio_id, event_id, fighter_id, picked_at) VALUES " +
                "($p, $past, $f, now()), ($p, $future, $f, now())",
        )

        assertEquals(1, jobs.runCaptainLock(Instant.parse("2026-12-10T12:00:00Z")))
        assertEquals(0, jobs.runCaptainLock(Instant.parse("2026-12-10T12:05:00Z")))

        assertEquals(listOf("$past"), fx.query("SELECT event_id FROM captain_pick WHERE locked_at IS NOT NULL"))
        assertEquals(1, captains.lockDue(Instant.parse("2026-12-21T00:00:00Z")))
    }
}
