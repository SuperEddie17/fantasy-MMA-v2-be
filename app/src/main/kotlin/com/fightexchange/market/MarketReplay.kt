package com.fightexchange.market

import com.fightexchange.rules.RuleSet
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

data class ReplayResult(
    val from: Instant,
    val to: Instant,
    val steps: Int,
    val listings: Int,
    val priceEvents: Int,
    val dailyRows: Int,
)

/**
 * Rebuilds the market from the sport data (fighters, bouts, results, odds, rankings, Elo) in time order, for use
 * after data was corrected. Everything before `from` is replayed quietly (it rebuilds the listings' state, the
 * existing `price_event` and `price_daily` rows before `from` stay as they are); from `from` on, price events are
 * rewritten and the daily rows closed again, with the inactivity job run for every day as it would have been.
 *
 * What the tables no longer know is approximated: a bout is announced at its earliest known announcement time
 * with its current fighters and type, a cancellation happens at `bout.cancelled_at` (the event start for older
 * ones), a result at the event start. Bouts completed before `from` are only settled (their announcement bump
 * would be gone by then); fighters they listed get their IPO at the first moment the replay meets them.
 */
@Singleton
class MarketReplay(
    private val tx: TransactionOperations<Connection>,
    private val market: MarketService,
    private val maintenance: MarketMaintenance,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(MarketReplay::class.java)

    /** Replays up to [to] (now by default). Returns null when a market job or another replay holds the lock. */
    fun replay(
        from: Instant,
        to: Instant = clock.instant(),
        rules: RuleSet = RuleSet.V2,
    ): ReplayResult? =
        tx.executeWrite { status ->
            val conn = status.connection
            if (!tryJobLock(conn, INACTIVITY_LOCK) || !tryJobLock(conn, PRICE_DAILY_LOCK)) return@executeWrite null
            wipe(conn, from)
            val steps = timeline(conn, from, to, rules)
            var caughtUp = false
            for (step in steps) {
                if (!caughtUp && step.at >= from) {
                    maintenance.applyInactivity(conn, from, rules, record = false)
                    caughtUp = true
                }
                step.run(conn)
            }
            if (!caughtUp) maintenance.applyInactivity(conn, from, rules, record = false)
            val daily = closeDays(conn, from, to)
            ReplayResult(from, to, steps.size, count(conn, "market_listing"), eventsSince(conn, from), daily)
                .also { log.info("Market replay from {} to {}: {}", from, to, it) }
        }

    private class Step(
        val at: Instant,
        val order: Int,
        val run: (Connection) -> Unit,
    )

    private fun wipe(
        conn: Connection,
        from: Instant,
    ) {
        conn.prepareStatement("DELETE FROM price_event WHERE at >= ?").use { ps ->
            ps.setTimestamp(1, Timestamp.from(from))
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM price_daily WHERE day >= ?").use { ps ->
            ps.setObject(1, from.atZone(ZoneOffset.UTC).toLocalDate())
            ps.executeUpdate()
        }
        conn.createStatement().use { s ->
            s.execute("DELETE FROM bout_price_bump; DELETE FROM bout_price_drift; DELETE FROM market_listing")
        }
    }

    private fun closeDays(
        conn: Connection,
        from: Instant,
        to: Instant,
    ): Int {
        val last = to.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        return generateSequence(from.atZone(ZoneOffset.UTC).toLocalDate()) { it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .sumOf { maintenance.closeDay(conn, it) }
    }

    /** Collects the replay steps of one run; each step runs with a cause quiet before `from` and Elo as of its time. */
    private inner class Timeline(
        val from: Instant,
        val to: Instant,
        val rules: RuleSet,
    ) {
        val steps = mutableListOf<Step>()

        fun add(
            at: Instant,
            order: Int,
            run: (Connection, PriceCause) -> Unit,
        ) {
            if (at <= to) steps += Step(at, order) { c -> run(c, cause(at, from)) }
        }

        fun bout(b: ReplayBout) {
            if (!(b.status == "COMPLETED" && b.startsAt < from)) announceAndDrift(b)
            if (b.status == "CANCELLED") {
                add(b.cancelledAt ?: b.startsAt, ORDER_CANCEL) { c, cause ->
                    val why = cause.copy(kind = PriceEventKind.CANCELLED, boutId = b.id)
                    market.cancelBout(c, b.id, b.withdrawn, b.cancelReason, why, rules)
                }
            }
            if (b.status == "COMPLETED") {
                add(b.startsAt, ORDER_RESULT) { c, cause ->
                    val why = cause.copy(kind = PriceEventKind.RESULT_RESET, boutId = b.id)
                    market.settleBout(c, b.id, why, rules, refreshLater = false)
                }
            }
        }

        private fun announceAndDrift(b: ReplayBout) {
            add(minOf(b.announcedAt, b.startsAt), ORDER_ANNOUNCE) { c, cause ->
                val why = cause.copy(kind = PriceEventKind.ANNOUNCED, boutId = b.id)
                market.syncBout(c, b.id, why, rules, asOpen = true)
            }
            val end = if (b.status == "CANCELLED") b.cancelledAt ?: b.startsAt else b.startsAt
            for (takenAt in b.oddsTakenAt.filter { it < end }) {
                add(takenAt, ORDER_ODDS) { c, cause ->
                    val why = cause.copy(kind = PriceEventKind.ODDS_DRIFT, boutId = b.id)
                    market.driftBout(c, b.id, why, rules, asOpen = true)
                }
            }
        }

        /** The inactivity job at 03:00 UTC of every day from `from` on. */
        fun inactivity() {
            var day = from.atZone(ZoneOffset.UTC).toLocalDate()
            while (true) {
                val at = day.atTime(INACTIVITY_TIME).toInstant(ZoneOffset.UTC)
                if (at > to) break
                if (at >=
                    from
                ) {
                    add(
                        at,
                        ORDER_INACTIVITY,
                    ) { c, cause -> maintenance.applyInactivity(c, at, rules, cause.record) }
                }
                day = day.plusDays(1)
            }
        }
    }

    private fun timeline(
        conn: Connection,
        from: Instant,
        to: Instant,
        rules: RuleSet,
    ): List<Step> {
        val t = Timeline(from, to, rules)
        fighters(conn).forEach { (id, createdAt) ->
            t.add(createdAt, ORDER_IPO) { c, cause -> market.ensureListed(c, id, cause, rules) }
        }
        bouts(conn).forEach(t::bout)
        rankings(conn).forEach { (division, takenAt) ->
            t.add(takenAt, ORDER_RANKING) { c, cause ->
                market.applyRanking(c, division, takenAt, cause.copy(kind = PriceEventKind.RANKING), rules)
            }
        }
        t.inactivity()
        return t.steps.sortedWith(compareBy({ it.at }, { it.order }))
    }

    /** Quiet before `from`; Elo as of the step's own time. */
    private fun cause(
        at: Instant,
        from: Instant,
    ) = PriceCause(PriceEventKind.ELO, at, record = at >= from, eloAsOf = at)

    private data class ReplayBout(
        val id: Long,
        val status: String,
        val announcedAt: Instant,
        val startsAt: Instant,
        val cancelledAt: Instant?,
        val withdrawn: Long?,
        val cancelReason: String?,
        val oddsTakenAt: List<Instant>,
    )

    private fun fighters(conn: Connection): List<Pair<Long, Instant>> =
        conn.createStatement().use { s ->
            val sql = "SELECT id, created_at FROM fighter WHERE merged_into_id IS NULL ORDER BY id"
            s.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add(rs.getLong(1) to rs.getTimestamp(2).toInstant()) }
            }
        }

    private fun bouts(conn: Connection): List<ReplayBout> {
        val odds =
            conn.createStatement().use { s ->
                s.executeQuery("SELECT bout_id, taken_at FROM odds_snapshot ORDER BY taken_at, id").use { rs ->
                    buildMap<Long, MutableList<Instant>> {
                        while (rs.next()) {
                            getOrPut(
                                rs.getLong(1),
                            ) { mutableListOf() }.add(rs.getTimestamp(2).toInstant())
                        }
                    }
                }
            }
        return conn.createStatement().use { s ->
            s
                .executeQuery(
                    "SELECT b.id, b.status, COALESCE(b.announced_at, b.created_at), e.starts_at, b.cancelled_at, " +
                        "b.withdrawn_fighter_id, b.cancel_reason FROM bout b JOIN event e ON e.id = b.event_id " +
                        "WHERE b.status IN ('ANNOUNCED', 'CONFIRMED', 'CANCELLED', 'COMPLETED') ORDER BY b.id",
                ).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ReplayBout(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    rs.getTimestamp(3).toInstant(),
                                    rs.getTimestamp(4).toInstant(),
                                    rs.getTimestamp(5)?.toInstant(),
                                    rs.getObject(6) as Long?,
                                    rs.getString(7),
                                    odds[rs.getLong(1)].orEmpty(),
                                ),
                            )
                        }
                    }
                }
        }
    }

    private fun rankings(conn: Connection): List<Pair<String, Instant>> =
        conn.createStatement().use { s ->
            s
                .executeQuery("SELECT DISTINCT division, taken_at FROM ranking_snapshot ORDER BY taken_at, division")
                .use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getTimestamp(2).toInstant()) } }
        }

    private fun count(
        conn: Connection,
        table: String,
    ): Int =
        conn.createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM $table").use {
                it.next()
                it.getInt(1)
            }
        }

    private fun eventsSince(
        conn: Connection,
        from: Instant,
    ): Int =
        conn.prepareStatement("SELECT count(*) FROM price_event WHERE at >= ?").use { ps ->
            ps.setTimestamp(1, Timestamp.from(from))
            ps.executeQuery().use {
                it.next()
                it.getInt(1)
            }
        }

    private companion object {
        const val ORDER_IPO = 0
        const val ORDER_ANNOUNCE = 1
        const val ORDER_ODDS = 2
        const val ORDER_RANKING = 3
        const val ORDER_CANCEL = 4
        const val ORDER_INACTIVITY = 5
        const val ORDER_RESULT = 6
        val INACTIVITY_TIME: LocalTime = LocalTime.of(3, 0)
    }
}
