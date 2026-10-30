package com.fightexchange.payout

import com.fightexchange.trading.CursorPage
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection

@Singleton
class DividendReads(
    private val tx: TransactionOperations<Connection>,
) {
    /** Newest first by `id`; [after] is the id of the last item of the previous page. */
    fun list(
        portfolioId: Long,
        after: Long?,
        limit: Int,
    ): CursorPage<DividendDto> =
        tx.executeRead { status ->
            val rows =
                status.connection
                    .prepareStatement(
                        "SELECT d.id, d.bout_id, d.fighter_id, f.name, d.kind, d.price, d.base, d.odds_used, " +
                            "d.bonuses, " +
                            "d.captain, d.amount, d.result_version, d.reversed_at IS NOT NULL, d.created_at " +
                            "FROM dividend_payout d JOIN fighter f ON f.id = d.fighter_id WHERE d.portfolio_id = ? " +
                            (if (after != null) "AND d.id < ? " else "") + "ORDER BY d.id DESC LIMIT ?",
                    ).use { ps ->
                        var i = 1
                        ps.setLong(i++, portfolioId)
                        if (after != null) ps.setLong(i++, after)
                        ps.setInt(i, limit + 1)
                        ps.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        DividendDto(
                                            rs.getLong(1),
                                            rs.getLong(2),
                                            rs.getLong(3),
                                            rs.getString(4),
                                            rs.getString(5),
                                            rs.getLong(6),
                                            rs.getLong(7),
                                            rs.getObject(8) as Double?,
                                            rs.getLong(9),
                                            rs.getBoolean(10),
                                            rs.getLong(11),
                                            rs.getInt(12),
                                            rs.getBoolean(13),
                                            rs.getTimestamp(14).toInstant(),
                                        ),
                                    )
                                }
                            }
                        }
                    }
            val shown = rows.take(limit)
            CursorPage(shown, if (rows.size > limit) shown.last().id else null)
        }
}
