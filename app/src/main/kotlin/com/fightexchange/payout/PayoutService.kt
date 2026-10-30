package com.fightexchange.payout

import com.fightexchange.rules.Dividend
import com.fightexchange.rules.DividendBonuses
import com.fightexchange.rules.Elo
import com.fightexchange.rules.FightOutcome
import com.fightexchange.rules.FightResult
import com.fightexchange.rules.Money
import com.fightexchange.rules.RuleSet
import com.fightexchange.trading.RuleSets
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant

/**
 * Pays dividends and settles shorts when a bout result is stored (plan.md, F4). Runs on the transaction of the
 * ingest handler, so a result and its payouts are written together.
 *
 * Idempotent per bout and `result_version`: a second call for the same result changes nothing. A corrected
 * result reverses every payout of the old version (`REVERSAL` ledger entries) and pays again at the same price.
 */
@Singleton
class PayoutService(
    private val rules: RuleSets,
) {
    /** Returns the number of payout rows written (zero payouts included). */
    fun settle(
        conn: Connection,
        boutId: Long,
        at: Instant,
    ): Int {
        val bout = loadBout(conn, boutId)
        val result = loadResult(conn, boutId)
        if (bout == null || result == null) return 0
        val rs = rules.at(at)
        lockListings(conn, bout)
        lockPortfolios(conn, bout)
        val current = currentPayouts(conn, boutId)
        if (current.isNotEmpty() && current.all { it.resultVersion == result.version }) return 0
        val targets =
            if (current.isEmpty()) {
                newTargets(conn, bout, rs)
            } else {
                current.forEach { reverse(conn, it, at) }
                current.map { Target(it.lotId, it.portfolioId, it.fighterId, it.kind, it.price) }
            }
        val ctx = Context(bout, result, oddsFor(conn, bout), captains(conn, bout.eventId), bonuses(conn, boutId), rs)
        for (t in targets) pay(conn, ctx, t, at)
        return targets.size
    }

    /** What every payout of one bout is calculated from. */
    private class Context(
        val bout: BoutRow,
        val result: ResultRow,
        val odds: Odds,
        val captains: Map<Long, Long>,
        val bonuses: Bonuses,
        val rules: RuleSet,
    )

    private class BoutRow(
        val id: Long,
        val eventId: Long,
        val a: Long,
        val b: Long,
        val announcedAt: Instant?,
        val startsAt: Instant,
    )

    private class ResultRow(
        val version: Int,
        val outcome: String,
        val winnerId: Long?,
        val method: String,
        val round: Int?,
        val titleWon: Boolean,
    ) {
        val finish get() = method == "KO_TKO" || method == "SUB"
    }

    private class Target(
        val lotId: Long,
        val portfolioId: Long,
        val fighterId: Long,
        val kind: String,
        val price: Long,
    )

    private class OldPayout(
        val id: Long,
        val portfolioId: Long,
        val fighterId: Long,
        val lotId: Long,
        val kind: String,
        val price: Long,
        val amount: Long,
        val ledgerEntryId: Long?,
        val resultVersion: Int,
    )

    private class Odds(
        val a: Double,
        val b: Double,
    )

    private class Bonuses(
        val night: Set<Long>,
        val misses: Set<Long>,
    )

    private fun loadBout(
        conn: Connection,
        boutId: Long,
    ): BoutRow? =
        conn
            .prepareStatement(
                "SELECT b.id, b.event_id, b.fighter_a_id, b.fighter_b_id, b.announced_at, e.starts_at " +
                    "FROM bout b JOIN event e ON e.id = b.event_id WHERE b.id = ?",
            ).use { ps ->
                ps.setLong(1, boutId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    BoutRow(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                        rs.getLong(4),
                        rs.getTimestamp(5)?.toInstant(),
                        rs.getTimestamp(6).toInstant(),
                    )
                }
            }

    private fun loadResult(
        conn: Connection,
        boutId: Long,
    ): ResultRow? =
        conn
            .prepareStatement(
                "SELECT version, outcome, winner_id, method, round, title_won FROM bout_result " +
                    "WHERE bout_id = ? AND is_current",
            ).use { ps ->
                ps.setLong(1, boutId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    ResultRow(
                        rs.getInt(1),
                        rs.getString(2),
                        rs.getObject(3) as Long?,
                        rs.getString(4),
                        rs.getObject(5) as Int?,
                        rs.getBoolean(6),
                    )
                }
            }

    /** Listing before portfolio, the same order as trades and delisting, so none of them can deadlock with us. */
    private fun lockListings(
        conn: Connection,
        bout: BoutRow,
    ) {
        conn
            .prepareStatement(
                "SELECT fighter_id FROM market_listing WHERE fighter_id IN (?, ?) ORDER BY fighter_id FOR UPDATE",
            ).use { ps ->
                ps.setLong(1, bout.a)
                ps.setLong(2, bout.b)
                ps.executeQuery().use { rs -> while (rs.next()) rs.getLong(1) }
            }
    }

    private fun lockPortfolios(
        conn: Connection,
        bout: BoutRow,
    ) {
        conn
            .prepareStatement(
                "SELECT id FROM portfolio WHERE id IN (" +
                    "SELECT portfolio_id FROM position_lot WHERE fighter_id IN (?, ?) AND closed_at IS NULL " +
                    "UNION SELECT portfolio_id FROM dividend_payout WHERE bout_id = ?) ORDER BY id FOR UPDATE",
            ).use { ps ->
                ps.setLong(1, bout.a)
                ps.setLong(2, bout.b)
                ps.setLong(3, bout.id)
                ps.executeQuery().use { rs -> while (rs.next()) rs.getLong(1) }
            }
    }

    private fun currentPayouts(
        conn: Connection,
        boutId: Long,
    ): List<OldPayout> =
        conn
            .prepareStatement(
                "SELECT id, portfolio_id, fighter_id, lot_id, kind, price, amount, ledger_entry_id, result_version " +
                    "FROM dividend_payout WHERE bout_id = ? AND reversed_at IS NULL ORDER BY id",
            ).use { ps ->
                ps.setLong(1, boutId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                OldPayout(
                                    rs.getLong(1),
                                    rs.getLong(2),
                                    rs.getLong(3),
                                    rs.getLong(4),
                                    rs.getString(5),
                                    rs.getLong(6),
                                    rs.getLong(7),
                                    rs.getObject(8) as Long?,
                                    rs.getInt(9),
                                ),
                            )
                        }
                    }
                }
            }

    /**
     * Lots bought before the bout was announced. Longs only count for portfolios that held at least
     * `minRoster` fighters at the time of the event; shorts do not need a full roster.
     */
    private fun newTargets(
        conn: Connection,
        bout: BoutRow,
        rs: RuleSet,
    ): List<Target> {
        val announced = bout.announcedAt ?: return emptyList()
        return conn
            .prepareStatement(
                "SELECT p.id, p.portfolio_id, p.fighter_id, p.side, l.price FROM position_lot p " +
                    "JOIN market_listing l ON l.fighter_id = p.fighter_id " +
                    "WHERE p.fighter_id IN (?, ?) AND p.closed_at IS NULL AND p.opened_at < ? " +
                    "AND (p.side = 'SHORT' OR (SELECT count(*) FROM position_lot r " +
                    "WHERE r.portfolio_id = p.portfolio_id " +
                    "AND r.side = 'LONG' AND r.opened_at <= ? AND (r.closed_at IS NULL OR r.closed_at > ?)) >= ?) " +
                    "ORDER BY p.portfolio_id, p.id",
            ).use { ps ->
                ps.setLong(1, bout.a)
                ps.setLong(2, bout.b)
                ps.setTimestamp(3, Timestamp.from(announced))
                ps.setTimestamp(4, Timestamp.from(bout.startsAt))
                ps.setTimestamp(5, Timestamp.from(bout.startsAt))
                ps.setInt(6, rs.minRoster)
                ps.executeQuery().use { r ->
                    buildList {
                        while (r.next()) {
                            add(
                                Target(
                                    r.getLong(1),
                                    r.getLong(2),
                                    r.getLong(3),
                                    if (r.getString(4) == "LONG") "LONG" else "SHORT",
                                    r.getLong(5),
                                ),
                            )
                        }
                    }
                }
            }
    }

    /** Closing odds, else the latest snapshot (admin odds included), else odds implied by the Elo ratings. */
    private fun oddsFor(
        conn: Connection,
        bout: BoutRow,
    ): Odds {
        fun snapshot(closing: Boolean): Odds? =
            conn
                .prepareStatement(
                    "SELECT odds_a, odds_b FROM odds_snapshot WHERE bout_id = ?" +
                        (if (closing) " AND is_closing" else "") + " ORDER BY taken_at DESC, id DESC LIMIT 1",
                ).use { ps ->
                    ps.setLong(1, bout.id)
                    ps.executeQuery().use { rs -> if (rs.next()) Odds(rs.getDouble(1), rs.getDouble(2)) else null }
                }
        return snapshot(true) ?: snapshot(false) ?: eloOdds(conn, bout)
    }

    private fun eloOdds(
        conn: Connection,
        bout: BoutRow,
    ): Odds {
        val a = eloBefore(conn, bout.a, bout.startsAt)
        val b = eloBefore(conn, bout.b, bout.startsAt)
        return Odds(1.0 / Elo.expected(a, b), 1.0 / Elo.expected(b, a))
    }

    private fun eloBefore(
        conn: Connection,
        fighterId: Long,
        startsAt: Instant,
    ): Double =
        conn
            .prepareStatement(
                "SELECT rating_after FROM elo_rating WHERE fighter_id = ? AND rated_at < ? " +
                    "ORDER BY rated_at DESC, id DESC LIMIT 1",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.setTimestamp(2, Timestamp.from(startsAt))
                ps.executeQuery().use { rs -> if (rs.next()) rs.getDouble(1) else RuleSet.V2.eloStart }
            }

    private fun captains(
        conn: Connection,
        eventId: Long,
    ): Map<Long, Long> =
        conn.prepareStatement("SELECT portfolio_id, fighter_id FROM captain_pick WHERE event_id = ?").use { ps ->
            ps.setLong(1, eventId)
            ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getLong(1), rs.getLong(2)) } }
        }

    private fun bonuses(
        conn: Connection,
        boutId: Long,
    ): Bonuses {
        fun ids(table: String): Set<Long> =
            conn.prepareStatement("SELECT fighter_id FROM $table WHERE bout_id = ?").use { ps ->
                ps.setLong(1, boutId)
                ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } }
            }
        return Bonuses(ids("bout_bonus"), ids("weight_miss"))
    }

    private fun reverse(
        conn: Connection,
        old: OldPayout,
        at: Instant,
    ) {
        if (old.ledgerEntryId != null && old.amount != 0L) {
            conn
                .prepareStatement(
                    "INSERT INTO ledger_entry (portfolio_id, at, kind, amount, bout_id, reverses_id) " +
                        "SELECT portfolio_id, ?, 'REVERSAL', -amount, bout_id, id FROM ledger_entry WHERE id = ?",
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp.from(at))
                    ps.setLong(2, old.ledgerEntryId)
                    ps.executeUpdate()
                }
            addCash(conn, old.portfolioId, -old.amount)
        }
        conn.prepareStatement("UPDATE dividend_payout SET reversed_at = ? WHERE id = ?").use { ps ->
            ps.setTimestamp(1, Timestamp.from(at))
            ps.setLong(2, old.id)
            ps.executeUpdate()
        }
    }

    private fun pay(
        conn: Connection,
        ctx: Context,
        t: Target,
        at: Instant,
    ) {
        val bout = ctx.bout
        val result = ctx.result
        val odds = ctx.odds
        val bonuses = ctx.bonuses
        val rs = ctx.rules
        val own = if (t.fighterId == bout.a) odds.a else odds.b
        val opponent = if (t.fighterId == bout.a) odds.b else odds.a
        val outcome = fighterResult(result, t.fighterId)
        var base = 0L
        var bonus = 0L
        var captain = false
        val amount: Long
        if (t.kind == "LONG") {
            captain = ctx.captains[t.portfolioId] == t.fighterId
            val d =
                Dividend.forWinner(
                    FightOutcome(outcome, result.finish, result.round ?: 0),
                    own,
                    Money(t.price),
                    captain,
                    DividendBonuses(
                        fightNight = t.fighterId in bonuses.night,
                        titleWon = result.titleWon && outcome == FightResult.WIN,
                        weightMiss = t.fighterId in bonuses.misses,
                    ),
                    rs,
                )
            base = d.core.units
            bonus = d.bonuses.units
            amount = d.total.units
        } else {
            amount = Dividend.forShort(outcome, own, opponent, rs).units
            base = amount
        }
        val ledgerId = if (amount != 0L) book(conn, t, bout.id, amount, at) else null
        conn
            .prepareStatement(
                "INSERT INTO dividend_payout (portfolio_id, bout_id, fighter_id, lot_id, kind, price, base, " +
                    "odds_used, bonuses, captain, amount, ledger_entry_id, result_version) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, t.portfolioId)
                ps.setLong(2, bout.id)
                ps.setLong(3, t.fighterId)
                ps.setLong(4, t.lotId)
                ps.setString(5, t.kind)
                ps.setLong(6, t.price)
                ps.setLong(7, base)
                ps.setDouble(8, own)
                ps.setLong(9, bonus)
                ps.setBoolean(10, captain)
                ps.setLong(11, amount)
                ps.setObject(12, ledgerId)
                ps.setInt(13, result.version)
                ps.executeUpdate()
            }
    }

    private fun fighterResult(
        result: ResultRow,
        fighterId: Long,
    ): FightResult =
        when (result.outcome) {
            "WIN" -> if (result.winnerId == fighterId) FightResult.WIN else FightResult.LOSS
            "DRAW" -> FightResult.DRAW
            else -> FightResult.NO_CONTEST
        }

    private fun book(
        conn: Connection,
        t: Target,
        boutId: Long,
        amount: Long,
        at: Instant,
    ): Long {
        val kind = if (t.kind == "LONG") "DIVIDEND" else "SHORT_SETTLE"
        val id =
            conn
                .prepareStatement(
                    "INSERT INTO ledger_entry (portfolio_id, at, kind, amount, bout_id) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    ps.setLong(1, t.portfolioId)
                    ps.setTimestamp(2, Timestamp.from(at))
                    ps.setString(3, kind)
                    ps.setLong(4, amount)
                    ps.setLong(5, boutId)
                    ps.executeUpdate()
                    ps.generatedKeys.use { r: ResultSet ->
                        r.next()
                        r.getLong(1)
                    }
                }
        addCash(conn, t.portfolioId, amount)
        return id
    }

    private fun addCash(
        conn: Connection,
        portfolioId: Long,
        amount: Long,
    ) {
        conn.prepareStatement("UPDATE portfolio SET cash_cached = cash_cached + ? WHERE id = ?").use { ps ->
            ps.setLong(1, amount)
            ps.setLong(2, portfolioId)
            ps.executeUpdate()
        }
    }
}
