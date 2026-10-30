package com.fightexchange.trading

import com.fightexchange.rules.Roster
import com.fightexchange.rules.RosterFighter
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

data class FighterInfo(
    val id: Long,
    val name: String,
    val ufc: Boolean,
    val tradable: Boolean,
    val price: Long,
)

/** The announced bout of a fighter: who they face and the latest odds on them. */
data class AnnouncedBout(
    val opponentId: Long,
    val odds: Double?,
)

data class PortfolioState(
    val seasonId: Long,
    val cash: Long,
)

/** Read queries the trading rules need, all on the caller's transaction connection. */
@Singleton
class TradingReads {
    /** The portfolio row, locked for the rest of the transaction when [forUpdate]. */
    fun portfolio(
        conn: Connection,
        portfolioId: Long,
        forUpdate: Boolean,
    ): PortfolioState? =
        conn
            .prepareStatement(
                "SELECT season_id, cash_cached FROM portfolio WHERE id = ?" + if (forUpdate) " FOR UPDATE" else "",
            ).use { ps ->
                ps.setLong(1, portfolioId)
                ps.executeQuery().use { rs -> if (rs.next()) PortfolioState(rs.getLong(1), rs.getLong(2)) else null }
            }

    /**
     * Takes a shared lock on the fighter's listing for the rest of the transaction. A delisting updates the row, so
     * it waits for trades in flight and trades that start later wait for it and then see the fighter delisted.
     * Always taken before the portfolio lock (same order as the delisting).
     */
    fun lockListing(
        conn: Connection,
        fighterId: Long,
    ) {
        conn.prepareStatement("SELECT 1 FROM market_listing WHERE fighter_id = ? FOR SHARE").use { ps ->
            ps.setLong(1, fighterId)
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    /** The fighter with their listing; null when the fighter does not exist or was never listed. */
    fun fighter(
        conn: Connection,
        fighterId: Long,
    ): FighterInfo? =
        conn
            .prepareStatement(
                "SELECT f.id, f.name, o.code = 'UFC', " +
                    "l.status IN ('ACTIVE', 'IPO') AND f.merged_into_id IS NULL, l.price " +
                    "FROM fighter f JOIN organization o ON o.id = f.organization_id " +
                    "JOIN market_listing l ON l.fighter_id = f.id WHERE f.id = ?",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        FighterInfo(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4), rs.getLong(5))
                    } else {
                        null
                    }
                }
            }

    /**
     * Trading in a fighter is closed from the weigh-in (the event start when none is known) of any bout of theirs
     * until its result is confirmed (or the bout is cancelled).
     */
    fun locked(
        conn: Connection,
        fighterId: Long,
        now: Instant,
    ): Boolean =
        conn
            .prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM bout b JOIN event e ON e.id = b.event_id " +
                    "WHERE (b.fighter_a_id = ? OR b.fighter_b_id = ?) " +
                    "AND b.status IN ('ANNOUNCED', 'CONFIRMED', 'NEEDS_REVIEW') " +
                    "AND COALESCE(e.weigh_in_at, e.starts_at) <= ?)",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.setLong(2, fighterId)
                ps.setTimestamp(3, Timestamp.from(now))
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }

    /** The next open bout of the fighter (earliest event), with the latest odds on the fighter, if any. */
    fun announcedBout(
        conn: Connection,
        fighterId: Long,
    ): AnnouncedBout? =
        conn
            .prepareStatement(
                "SELECT CASE WHEN b.fighter_a_id = ? THEN b.fighter_b_id ELSE b.fighter_a_id END, " +
                    "(SELECT CASE WHEN b.fighter_a_id = ? THEN o.odds_a ELSE o.odds_b END FROM odds_snapshot o " +
                    " WHERE o.bout_id = b.id ORDER BY o.taken_at DESC, o.id DESC LIMIT 1) " +
                    "FROM bout b JOIN event e ON e.id = b.event_id " +
                    "WHERE (b.fighter_a_id = ? OR b.fighter_b_id = ?) " +
                    "AND b.status IN ('ANNOUNCED', 'CONFIRMED', 'NEEDS_REVIEW') " +
                    "ORDER BY e.starts_at, b.id LIMIT 1",
            ).use { ps ->
                repeat(4) { ps.setLong(it + 1, fighterId) }
                ps.executeQuery().use { rs ->
                    if (rs.next()) AnnouncedBout(rs.getLong(1), rs.getObject(2) as Double?) else null
                }
            }

    /** Open positions of the portfolio as roster limits see them. */
    fun roster(
        conn: Connection,
        portfolioId: Long,
    ): Roster {
        val (longs, shorts) =
            conn
                .prepareStatement(
                    "SELECT p.fighter_id, p.side, o.code = 'UFC' FROM position_lot p " +
                        "JOIN fighter f ON f.id = p.fighter_id JOIN organization o ON o.id = f.organization_id " +
                        "WHERE p.portfolio_id = ? AND p.closed_at IS NULL ORDER BY p.id",
                ).use { ps ->
                    ps.setLong(1, portfolioId)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    rs.getString(2) to RosterFighter(rs.getLong(1), rs.getBoolean(3)),
                                )
                            }
                        }
                    }
                }.partition { it.first == "LONG" }
        return Roster(longs.map { it.second }, shorts.map { it.second })
    }

    fun buysInWeek(
        conn: Connection,
        portfolioId: Long,
        weekStart: LocalDate,
    ): Int =
        conn
            .prepareStatement("SELECT buys FROM weekly_buy_counter WHERE portfolio_id = ? AND week_start = ?")
            .use { ps ->
                ps.setLong(1, portfolioId)
                ps.setObject(2, weekStart)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
}
