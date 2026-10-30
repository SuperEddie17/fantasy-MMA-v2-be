package com.fightexchange.ranking

import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/** Daily net worth snapshots: cash + longs - shorts at the listing prices of the moment. */
@Singleton
class NetWorthService(
    private val tx: TransactionOperations<Connection>,
) {
    /**
     * Writes (or rewrites) the snapshot of [day] for every global portfolio of a season that is running at [now]
     * or ended within the last day. Returns the number of rows; running again changes nothing but the values.
     */
    fun snapshot(
        conn: Connection,
        day: LocalDate,
        now: Instant,
    ): Int =
        conn
            .prepareStatement(
                "INSERT INTO net_worth_snapshot (portfolio_id, day, cash, long_value, short_value, net_worth) " +
                    "SELECT p.id, ?, p.cash_cached, COALESCE(v.longs, 0), COALESCE(v.shorts, 0), " +
                    "p.cash_cached + COALESCE(v.longs, 0) - COALESCE(v.shorts, 0) " +
                    "FROM portfolio p JOIN season s ON s.id = p.season_id " +
                    "LEFT JOIN LATERAL (SELECT " +
                    "COALESCE(sum(CASE WHEN lot.side = 'LONG' THEN l.price END), 0)::bigint AS longs, " +
                    "COALESCE(sum(CASE WHEN lot.side = 'SHORT' THEN l.price END), 0)::bigint AS shorts " +
                    "FROM position_lot lot JOIN market_listing l ON l.fighter_id = lot.fighter_id " +
                    "WHERE lot.portfolio_id = p.id AND lot.closed_at IS NULL) v ON TRUE " +
                    "WHERE p.league_id IS NULL AND s.starts_at <= ? " +
                    "AND s.ends_at > ?::timestamptz - INTERVAL '1 day' " +
                    "ON CONFLICT (portfolio_id, day) DO UPDATE SET cash = EXCLUDED.cash, " +
                    "long_value = EXCLUDED.long_value, short_value = EXCLUDED.short_value, " +
                    "net_worth = EXCLUDED.net_worth",
            ).use { ps ->
                ps.setObject(1, day)
                ps.setTimestamp(2, Timestamp.from(now))
                ps.setTimestamp(3, Timestamp.from(now))
                ps.executeUpdate()
            }

    /** Snapshots of the portfolio from [from] to [to] (both included), oldest first. */
    fun history(
        portfolioId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<NetWorthPointDto> =
        tx.executeRead { status ->
            status.connection
                .prepareStatement(
                    "SELECT day, cash, long_value, short_value, net_worth FROM net_worth_snapshot " +
                        "WHERE portfolio_id = ? AND day BETWEEN ? AND ? ORDER BY day",
                ).use { ps ->
                    ps.setLong(1, portfolioId)
                    ps.setObject(2, from)
                    ps.setObject(3, to)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    NetWorthPointDto(
                                        rs.getObject(1, LocalDate::class.java),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getLong(4),
                                        rs.getLong(5),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
}
