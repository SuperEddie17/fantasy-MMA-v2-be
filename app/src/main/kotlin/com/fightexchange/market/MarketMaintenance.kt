package com.fightexchange.market

import com.fightexchange.rules.Inactivity
import com.fightexchange.rules.RuleSet
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.math.pow

/** Periodic market bookkeeping behind [MarketJobs]: inactivity decay and the daily OHLC rows. */
@Singleton
class MarketMaintenance(
    private val market: MarketService,
) {
    /**
     * Weekly inactivity decay as of [now]: every full week beyond `inactiveWeeks` since the last fight (or the IPO
     * for a fighter who never fought) multiplies the price by `1 - decay`, once per week however often this runs.
     * A fighter with an announced bout does not decay (the missed weeks are written off); beyond `delistWeeks`
     * without an announced bout the fighter is delisted. Returns the number of listings changed.
     * The first decay comes as soon as the gap exceeds `inactiveWeeks`, then one per further week (as `sim.py`
     * steps weekly). [record] false (a replay catching up) changes the listings without logging price events.
     */
    fun applyInactivity(
        conn: Connection,
        now: Instant,
        rules: RuleSet = RuleSet.V2,
        record: Boolean = true,
    ): Int {
        val rows =
            conn
                .prepareStatement(
                    "SELECT l.fighter_id, COALESCE(l.last_fight_at, l.listed_at), l.inactivity_ticks, " +
                        "EXISTS (SELECT 1 FROM bout_price_bump u WHERE u.fighter_id = l.fighter_id) " +
                        "FROM market_listing l " +
                        "WHERE l.status <> 'DELISTED' ORDER BY l.fighter_id",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    Inactive(
                                        rs.getLong(1),
                                        rs.getTimestamp(2).toInstant(),
                                        rs.getInt(3),
                                        rs.getBoolean(4),
                                    ),
                                )
                            }
                        }
                    }
                }
        var changed = 0
        for (row in rows) {
            val days = Duration.between(row.since, now).toDays()
            val weeks = days / 7.0
            val cause = PriceCause(PriceEventKind.INACTIVITY, now, record = record)
            val due = maxOf(0, ceil((days - rules.inactiveWeeks * 7.0) / 7.0).toInt())
            if (Inactivity.shouldDelist(weeks, row.announced, rules)) {
                market.delist(conn, row.fighterId, cause.copy(note = "no fight for ${weeks.toInt()} weeks"))
                changed++
                continue
            }
            val n = if (row.announced) 0 else due - row.ticks
            if (n > 0) {
                val factor = Inactivity.weeklyFactor(weeks, false, rules).pow(n)
                market.apply(
                    conn,
                    row.fighterId,
                    cause.copy(note = "$n week(s) without a fight, ${weeks.toInt()} in total"),
                    factor,
                    rules = rules,
                )
            }
            if (due != row.ticks) {
                conn.prepareStatement("UPDATE market_listing SET inactivity_ticks = ? WHERE fighter_id = ?").use { ps ->
                    ps.setInt(1, due)
                    ps.setLong(2, row.fighterId)
                    ps.executeUpdate()
                }
                changed++
            }
        }
        return changed
    }

    /**
     * Closes the OHLC row of every listing for the UTC [day] from the day's price events (open = price at the start
     * of the day). Re-running a day rewrites it, so a replay can correct history. Returns the rows written.
     */
    fun closeDay(
        conn: Connection,
        day: LocalDate,
    ): Int {
        val start = Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant())
        val end = Timestamp.from(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
        return conn
            .prepareStatement(
                """
                INSERT INTO price_daily (fighter_id, day, open, close, low, high)
                SELECT fighter_id, ?, open, COALESCE(last_after, open), LEAST(open, COALESCE(lo, open)),
                       GREATEST(open, COALESCE(hi, open))
                FROM (
                    SELECT l.fighter_id, COALESCE(p.price_after, d.first_before) AS open,
                           d.last_after, d.lo, d.hi
                    FROM market_listing l
                    LEFT JOIN (
                        SELECT DISTINCT ON (fighter_id) fighter_id, price_after FROM price_event
                        WHERE at < ? ORDER BY fighter_id, at DESC, id DESC
                    ) p ON p.fighter_id = l.fighter_id
                    LEFT JOIN (
                        SELECT fighter_id, (array_agg(price_before ORDER BY at, id))[1] AS first_before,
                               (array_agg(price_after ORDER BY at DESC, id DESC))[1] AS last_after,
                               min(LEAST(price_before, price_after)) AS lo, max(GREATEST(price_before, price_after)) AS hi
                        FROM price_event WHERE at >= ? AND at < ? GROUP BY fighter_id
                    ) d ON d.fighter_id = l.fighter_id
                    WHERE l.listed_at < ? AND (l.delisted_at IS NULL OR l.delisted_at >= ?)
                ) t WHERE open IS NOT NULL
                ON CONFLICT (fighter_id, day) DO UPDATE SET open = EXCLUDED.open, close = EXCLUDED.close,
                    low = EXCLUDED.low, high = EXCLUDED.high
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, day)
                ps.setTimestamp(2, start)
                ps.setTimestamp(3, start)
                ps.setTimestamp(4, end)
                ps.setTimestamp(5, end)
                ps.setTimestamp(6, start)
                ps.executeUpdate()
            }
    }

    private data class Inactive(
        val fighterId: Long,
        val since: Instant,
        val ticks: Int,
        val announced: Boolean,
    )
}
