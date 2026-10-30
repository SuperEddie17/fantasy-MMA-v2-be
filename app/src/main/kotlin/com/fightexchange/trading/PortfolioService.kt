package com.fightexchange.trading

import com.fightexchange.common.ApiException
import io.micronaut.http.HttpStatus
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

private const val UNIQUE_VIOLATION = "23505"

@Singleton
class PortfolioService(
    private val tx: TransactionOperations<Connection>,
    private val seasons: SeasonService,
    private val rules: RuleSets,
    private val clock: Clock,
) {
    /** Joins a season (the current one by default) with the starting budget, booked as `START_BUDGET`. */
    fun create(
        userId: Long,
        seasonId: Long?,
    ): PortfolioDto {
        val now = clock.instant()
        val id =
            try {
                tx.executeWrite { status ->
                    val conn = status.connection
                    val season = openSeason(conn, seasonId, now)
                    val budget = rules.at(season.startsAt).budget
                    val portfolioId =
                        conn
                            .prepareStatement(
                                "INSERT INTO portfolio (user_id, season_id, cash_cached) VALUES (?, ?, ?) RETURNING id",
                            ).use { ps ->
                                ps.setLong(1, userId)
                                ps.setLong(2, season.id)
                                ps.setLong(3, budget.units)
                                ps.executeQuery().use { rs ->
                                    rs.next()
                                    rs.getLong(1)
                                }
                            }
                    conn
                        .prepareStatement(
                            "INSERT INTO ledger_entry (portfolio_id, at, kind, amount) " +
                                "VALUES (?, ?, 'START_BUDGET', ?)",
                        ).use { ps ->
                            ps.setLong(1, portfolioId)
                            ps.setTimestamp(2, Timestamp.from(now))
                            ps.setLong(3, budget.units)
                            ps.executeUpdate()
                        }
                    portfolioId
                }
            } catch (e: java.sql.SQLException) {
                if (e.sqlState == UNIQUE_VIOLATION) {
                    throw ApiException(HttpStatus.CONFLICT, "PORTFOLIO_EXISTS", "You already joined this season")
                }
                throw e
            }
        return view(id)!!
    }

    private fun openSeason(
        conn: Connection,
        seasonId: Long?,
        now: Instant,
    ): SeasonDto {
        val season =
            if (seasonId != null) seasons.find(conn, seasonId) else seasons.current(conn, now)
        val failure =
            when {
                season == null && seasonId != null ->
                    Triple(HttpStatus.NOT_FOUND, "SEASON_NOT_FOUND", "Season does not exist")
                season == null -> Triple(HttpStatus.CONFLICT, "NO_ACTIVE_SEASON", "There is no season running now")
                !season.endsAt.isAfter(now) -> Triple(HttpStatus.CONFLICT, "SEASON_ENDED", "The season has ended")
                else -> return season
            }
        throw ApiException(failure.first, failure.second, failure.third)
    }

    fun ownedBy(userId: Long): List<PortfolioSummaryDto> =
        tx.executeRead { status ->
            status.connection
                .prepareStatement(
                    "SELECT id, season_id, cash_cached, created_at FROM portfolio WHERE user_id = ? ORDER BY id",
                ).use { ps ->
                    ps.setLong(1, userId)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    PortfolioSummaryDto(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getTimestamp(4).toInstant(),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    /** Owner of the portfolio, or null when it does not exist. */
    fun ownerOf(portfolioId: Long): Long? =
        tx.executeRead { status ->
            status.connection.prepareStatement("SELECT user_id FROM portfolio WHERE id = ?").use { ps ->
                ps.setLong(1, portfolioId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
        }

    /** Cash, open positions at current market prices and net worth; null when the portfolio does not exist. */
    fun view(portfolioId: Long): PortfolioDto? =
        tx.executeRead { status ->
            val conn = status.connection
            val head =
                conn
                    .prepareStatement("SELECT user_id, season_id, cash_cached, created_at FROM portfolio WHERE id = ?")
                    .use { ps ->
                        ps.setLong(1, portfolioId)
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                Head(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getTimestamp(4).toInstant())
                            } else {
                                null
                            }
                        }
                    } ?: return@executeRead null
            val positions = positions(conn, portfolioId)
            val longs = positions.filter { it.side == "LONG" }.sumOf { it.currentPrice }
            val shorts = positions.filter { it.side == "SHORT" }.sumOf { it.currentPrice }
            PortfolioDto(
                portfolioId,
                head.userId,
                head.seasonId,
                head.cash,
                longs,
                shorts,
                head.cash + longs - shorts,
                positions,
                head.createdAt,
            )
        }

    private data class Head(
        val userId: Long,
        val seasonId: Long,
        val cash: Long,
        val createdAt: Instant,
    )

    private fun positions(
        conn: Connection,
        portfolioId: Long,
    ): List<PositionDto> =
        conn
            .prepareStatement(
                "SELECT p.id, p.fighter_id, f.name, p.side, p.opened_at, p.open_price, l.price " +
                    "FROM position_lot p JOIN fighter f ON f.id = p.fighter_id " +
                    "JOIN market_listing l ON l.fighter_id = p.fighter_id " +
                    "WHERE p.portfolio_id = ? AND p.closed_at IS NULL ORDER BY p.id",
            ).use { ps ->
                ps.setLong(1, portfolioId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                PositionDto(
                                    rs.getLong(1),
                                    rs.getLong(2),
                                    rs.getString(3),
                                    rs.getString(4),
                                    rs.getTimestamp(5).toInstant(),
                                    rs.getLong(6),
                                    rs.getLong(7),
                                ),
                            )
                        }
                    }
                }
            }

    fun ledger(
        portfolioId: Long,
        after: Long?,
        limit: Int,
    ): CursorPage<LedgerEntryDto> =
        page(
            "SELECT id, at, kind, amount, trade_id, bout_id, reverses_id FROM ledger_entry",
            portfolioId,
            after,
            limit,
        ) { rs ->
            LedgerEntryDto(
                rs.getLong(1),
                rs.getTimestamp(2).toInstant(),
                rs.getString(3),
                rs.getLong(4),
                rs.getObject(5) as Long?,
                rs.getObject(6) as Long?,
                rs.getObject(7) as Long?,
            )
        }

    fun trades(
        portfolioId: Long,
        after: Long?,
        limit: Int,
    ): CursorPage<TradeDto> =
        page(
            "SELECT t.id, t.fighter_id, f.name, t.kind, t.price, t.fee, t.extra_fee, t.free_buy, t.at " +
                "FROM trade t JOIN fighter f ON f.id = t.fighter_id",
            portfolioId,
            after,
            limit,
            alias = "t.",
        ) { rs ->
            TradeDto(
                rs.getLong(1),
                rs.getLong(2),
                rs.getString(3),
                rs.getString(4),
                rs.getLong(5),
                rs.getLong(6),
                rs.getLong(7),
                rs.getBoolean(8),
                rs.getTimestamp(9).toInstant(),
            )
        }

    /** Newest first by `id`; [after] is the id of the last item of the previous page. */
    private fun <T> page(
        select: String,
        portfolioId: Long,
        after: Long?,
        limit: Int,
        alias: String = "",
        map: (java.sql.ResultSet) -> T,
    ): CursorPage<T> =
        tx.executeRead { status ->
            val rows =
                status.connection
                    .prepareStatement(
                        "$select WHERE ${alias}portfolio_id = ? " + (if (after != null) "AND ${alias}id < ? " else "") +
                            "ORDER BY ${alias}id DESC LIMIT ?",
                    ).use { ps ->
                        var i = 1
                        ps.setLong(i++, portfolioId)
                        if (after != null) ps.setLong(i++, after)
                        ps.setInt(i, limit + 1)
                        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1) to map(rs)) } }
                    }
            val shown = rows.take(limit)
            CursorPage(shown.map { it.second }, if (rows.size > limit) shown.last().first else null)
        }
}
