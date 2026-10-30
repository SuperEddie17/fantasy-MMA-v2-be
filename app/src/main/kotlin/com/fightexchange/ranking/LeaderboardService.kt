package com.fightexchange.ranking

import com.fightexchange.common.ApiException
import io.micronaut.http.HttpStatus
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

const val SEASON_PERIOD = "SEASON"
private val PERIOD_FORMAT = Regex("MONTH-\\d{4}-(0[1-9]|1[0-2])")
private val FAR_PAST: LocalDate = LocalDate.of(1900, 1, 1)

fun monthPeriod(month: YearMonth) = "MONTH-$month"

@Singleton
class LeaderboardService(
    private val tx: TransactionOperations<Connection>,
) {
    /** Ids of the seasons that are running at [now] or ended within the last day (the ones with fresh snapshots). */
    fun activeSeasons(
        conn: Connection,
        now: Instant,
    ): List<Long> =
        conn
            .prepareStatement(
                "SELECT id FROM season WHERE starts_at <= ? AND ends_at > ?::timestamptz - INTERVAL '1 day' " +
                    "ORDER BY id",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.setTimestamp(2, Timestamp.from(now))
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }

    /** Seasons that overlap the calendar [month]. */
    fun seasonsOverlapping(
        conn: Connection,
        month: YearMonth,
    ): List<Long> =
        conn
            .prepareStatement("SELECT id FROM season WHERE starts_at < ? AND ends_at > ? ORDER BY id")
            .use { ps ->
                ps.setTimestamp(
                    1,
                    Timestamp.from(
                        month
                            .plusMonths(1)
                            .atDay(1)
                            .atStartOfDay()
                            .toInstant(UTC),
                    ),
                )
                ps.setTimestamp(2, Timestamp.from(month.atDay(1).atStartOfDay().toInstant(UTC)))
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }

    /** Rebuilds the running overall ranking: net worth on the latest snapshot at or before [today]. */
    fun rebuildSeason(
        conn: Connection,
        seasonId: Long,
        today: LocalDate,
    ): Int = rebuild(conn, seasonId, SEASON_PERIOD, FAR_PAST, FAR_PAST, today, closed = false)

    /**
     * Rebuilds the sprint of [month]: change in net worth from the snapshot of its first day (the starting
     * budget for a portfolio that joined later) to the latest snapshot at or before [today]. With [close] the
     * ranking ends on the first day of the next month and is stored as final.
     */
    fun rebuildMonth(
        conn: Connection,
        seasonId: Long,
        month: YearMonth,
        today: LocalDate,
        close: Boolean,
    ): Int {
        val first = month.atDay(1)
        val end = if (close) month.plusMonths(1).atDay(1) else minOf(today, month.plusMonths(1).atDay(1))
        return rebuild(conn, seasonId, monthPeriod(month), first, first, end, closed = close)
    }

    private fun rebuild(
        conn: Connection,
        seasonId: Long,
        period: String,
        baselineDay: LocalDate,
        fromDay: LocalDate,
        endDay: LocalDate,
        closed: Boolean,
    ): Int {
        if (!closed && isClosed(conn, seasonId, period)) return 0
        conn.prepareStatement("DELETE FROM leaderboard_entry WHERE season_id = ? AND period = ?").use { ps ->
            ps.setLong(1, seasonId)
            ps.setString(2, period)
            ps.executeUpdate()
        }
        val order = if (period == SEASON_PERIOD) "nw" else "change"
        return conn
            .prepareStatement(
                "INSERT INTO leaderboard_entry (season_id, period, portfolio_id, rank, net_worth, change, closed) " +
                    "SELECT ?, ?, id, RANK() OVER (ORDER BY $order DESC), nw, change, ? FROM (" +
                    "SELECT p.id, e.net_worth AS nw, e.net_worth - COALESCE(" +
                    "(SELECT b.net_worth FROM net_worth_snapshot b WHERE b.portfolio_id = p.id AND b.day <= ? " +
                    "ORDER BY b.day DESC LIMIT 1), " +
                    "(SELECT amount FROM ledger_entry WHERE portfolio_id = p.id AND kind = 'START_BUDGET' " +
                    "ORDER BY id LIMIT 1)) AS change " +
                    "FROM portfolio p JOIN LATERAL (SELECT net_worth FROM net_worth_snapshot s " +
                    "WHERE s.portfolio_id = p.id AND s.day BETWEEN ? AND ? ORDER BY s.day DESC LIMIT 1) e ON TRUE " +
                    "WHERE p.season_id = ? AND p.league_id IS NULL) ranked",
            ).use { ps ->
                ps.setLong(1, seasonId)
                ps.setString(2, period)
                ps.setBoolean(3, closed)
                ps.setObject(4, baselineDay)
                ps.setObject(5, fromDay)
                ps.setObject(6, endDay)
                ps.setLong(7, seasonId)
                ps.executeUpdate()
            }
    }

    private fun isClosed(
        conn: Connection,
        seasonId: Long,
        period: String,
    ): Boolean =
        conn
            .prepareStatement(
                "SELECT 1 FROM leaderboard_entry WHERE season_id = ? AND period = ? AND closed LIMIT 1",
            ).use { ps ->
                ps.setLong(1, seasonId)
                ps.setString(2, period)
                ps.executeQuery().use { it.next() }
            }

    /** One page of a ranking. [seasonId] defaults to the running season, else the latest one. */
    fun read(
        seasonId: Long?,
        period: String?,
        offset: Int,
        limit: Int,
        now: Instant,
    ): LeaderboardDto {
        val p = period ?: SEASON_PERIOD
        if (p != SEASON_PERIOD && !PERIOD_FORMAT.matches(p)) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Invalid request",
                "period must be SEASON or MONTH-yyyy-MM",
            )
        }
        return tx.executeRead { status ->
            val conn = status.connection
            val season =
                seasonId ?: defaultSeason(conn, now)
                    ?: throw ApiException(HttpStatus.NOT_FOUND, "SEASON_NOT_FOUND", "There is no season")
            val rows = page(conn, season, p, offset, limit + 1)
            val shown = rows.take(limit)
            LeaderboardDto(
                season,
                p,
                isClosed(conn, season, p),
                shown,
                if (rows.size > limit) offset + limit else null,
            )
        }
    }

    private fun defaultSeason(
        conn: Connection,
        now: Instant,
    ): Long? =
        conn
            .prepareStatement(
                "SELECT id FROM season ORDER BY (starts_at <= ? AND ends_at > ?) DESC, starts_at DESC, id DESC LIMIT 1",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.setTimestamp(2, Timestamp.from(now))
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }

    private fun page(
        conn: Connection,
        seasonId: Long,
        period: String,
        offset: Int,
        limit: Int,
    ): List<LeaderboardRowDto> =
        conn
            .prepareStatement(
                "SELECT l.rank, l.portfolio_id, u.display_name, l.net_worth, l.change FROM leaderboard_entry l " +
                    "JOIN portfolio p ON p.id = l.portfolio_id JOIN app_user u ON u.id = p.user_id " +
                    "WHERE l.season_id = ? AND l.period = ? ORDER BY l.rank, l.portfolio_id LIMIT ? OFFSET ?",
            ).use { ps ->
                ps.setLong(1, seasonId)
                ps.setString(2, period)
                ps.setInt(3, limit)
                ps.setInt(4, offset)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                LeaderboardRowDto(
                                    rs.getInt(1),
                                    rs.getLong(2),
                                    rs.getString(3),
                                    rs.getLong(4),
                                    rs.getLong(5),
                                ),
                            )
                        }
                    }
                }
            }

    private companion object {
        val UTC: java.time.ZoneOffset = java.time.ZoneOffset.UTC
    }
}
