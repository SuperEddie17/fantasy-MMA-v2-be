package com.fightexchange.trading

import com.fightexchange.rules.Money
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant

/**
 * When a fighter leaves the market, every open lot in every portfolio is settled at the last price
 * (plan.md, F3.7): a long pays out the price, a short costs it (cash may go negative here, as for short settlement).
 * Each lot gets a `FORCED_SELL` trade without fees and a `DELIST_PAYOUT` ledger entry.
 * Runs on the delisting transaction, after the listing is marked delisted: trades take a shared lock on the
 * listing before the portfolio lock, so none can open a new lot once the delisting is under way.
 */
@Singleton
class DelistPayout {
    /** Returns the number of lots settled. */
    fun payOut(
        conn: Connection,
        fighterId: Long,
        price: Money,
        at: Instant,
    ): Int {
        lockHolders(conn, fighterId)
        val lots = openLots(conn, fighterId)
        for (lot in lots) settle(conn, lot, price, at)
        return lots.size
    }

    private class OpenLot(
        val id: Long,
        val portfolioId: Long,
        val fighterId: Long,
        val side: String,
    )

    /** Locks the portfolios holding the fighter, lowest id first, so concurrent delistings cannot deadlock. */
    private fun lockHolders(
        conn: Connection,
        fighterId: Long,
    ) {
        conn
            .prepareStatement(
                "SELECT id FROM portfolio WHERE id IN (SELECT portfolio_id FROM position_lot " +
                    "WHERE fighter_id = ? AND closed_at IS NULL) ORDER BY id FOR UPDATE",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.executeQuery().use { rs -> while (rs.next()) rs.getLong(1) }
            }
    }

    private fun openLots(
        conn: Connection,
        fighterId: Long,
    ): List<OpenLot> =
        conn
            .prepareStatement(
                "SELECT id, portfolio_id, fighter_id, side FROM position_lot " +
                    "WHERE fighter_id = ? AND closed_at IS NULL ORDER BY portfolio_id, id",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                OpenLot(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)),
                            )
                        }
                    }
                }
            }

    private fun settle(
        conn: Connection,
        lot: OpenLot,
        price: Money,
        at: Instant,
    ) {
        val tradeId =
            conn
                .prepareStatement(
                    "INSERT INTO trade (portfolio_id, fighter_id, kind, price, at) VALUES (?, ?, 'FORCED_SELL', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    ps.setLong(1, lot.portfolioId)
                    ps.setLong(2, lot.fighterId)
                    ps.setLong(3, price.units)
                    ps.setTimestamp(4, Timestamp.from(at))
                    ps.executeUpdate()
                    ps.generatedKeys.use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
        val amount = if (lot.side == "LONG") price.units else -price.units
        conn
            .prepareStatement(
                "INSERT INTO ledger_entry (portfolio_id, at, kind, amount, trade_id) " +
                    "VALUES (?, ?, 'DELIST_PAYOUT', ?, ?)",
            ).use { ps ->
                ps.setLong(1, lot.portfolioId)
                ps.setTimestamp(2, Timestamp.from(at))
                ps.setLong(3, amount)
                ps.setLong(4, tradeId)
                ps.executeUpdate()
            }
        conn
            .prepareStatement("UPDATE position_lot SET closed_at = ?, close_price = ?, close_trade_id = ? WHERE id = ?")
            .use { ps ->
                ps.setTimestamp(1, Timestamp.from(at))
                ps.setLong(2, price.units)
                ps.setLong(3, tradeId)
                ps.setLong(4, lot.id)
                ps.executeUpdate()
            }
        conn.prepareStatement("UPDATE portfolio SET cash_cached = cash_cached + ? WHERE id = ?").use { ps ->
            ps.setLong(1, amount)
            ps.setLong(2, lot.portfolioId)
            ps.executeUpdate()
        }
    }
}
