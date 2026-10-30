package com.fightexchange.market

import com.fightexchange.rules.Pricing
import com.fightexchange.rules.RuleSet
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val WINDOW = Duration.ofDays(7)

/**
 * Demand factor of every listing (plan.md, F3.6): net buys over the last 7 days (buys and short closes count
 * +1, sells and short opens -1, forced sells at delisting nothing) per active portfolio, see [Pricing.demandFactor].
 * A fighter nobody traded in the window goes back to factor 1. Idempotent: a repeated run changes nothing.
 */
@Singleton
class DemandService(
    private val market: MarketService,
) {
    /** Returns the number of listings whose demand factor changed. */
    fun update(
        conn: Connection,
        now: Instant,
        rules: RuleSet = RuleSet.V2,
    ): Int {
        val portfolios = activePortfolios(conn, now)
        val net = netBuys(conn, now)
        val listed =
            conn.createStatement().use { s ->
                s
                    .executeQuery(
                        "SELECT fighter_id FROM market_listing WHERE status <> 'DELISTED' ORDER BY fighter_id",
                    ).use { rs ->
                        buildList { while (rs.next()) add(rs.getLong(1)) }
                    }
            }
        var changed = 0
        for (fighterId in listed) {
            val netBuys = net[fighterId] ?: 0
            val cause =
                PriceCause(PriceEventKind.DEMAND, now, note = "net buys $netBuys over 7 days, $portfolios portfolios")
            if (market.setDemand(
                    conn,
                    fighterId,
                    Pricing.demandFactor(netBuys, portfolios, rules),
                    cause,
                    rules,
                )
            ) {
                changed++
            }
        }
        return changed
    }

    /** Portfolios in a season that is running at [now]. */
    private fun activePortfolios(
        conn: Connection,
        now: Instant,
    ): Int =
        conn
            .prepareStatement(
                "SELECT count(*) FROM portfolio p JOIN season s ON s.id = p.season_id " +
                    "WHERE s.starts_at <= ? AND s.ends_at > ?",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.setTimestamp(2, Timestamp.from(now))
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }

    private fun netBuys(
        conn: Connection,
        now: Instant,
    ): Map<Long, Int> =
        conn
            .prepareStatement(
                "SELECT fighter_id, sum(CASE WHEN kind IN ('BUY', 'SHORT_CLOSE') THEN 1 ELSE -1 END) FROM trade " +
                    "WHERE kind <> 'FORCED_SELL' AND at > ? AND at <= ? GROUP BY fighter_id",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(now.minus(WINDOW)))
                ps.setTimestamp(2, Timestamp.from(now))
                ps.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put(rs.getLong(1), rs.getInt(2)) }
                }
            }
}

/** Hourly demand update; the Postgres advisory lock lets only one app instance run it. */
@Singleton
open class DemandJob(
    private val tx: TransactionOperations<Connection>,
    private val demand: DemandService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(DemandJob::class.java)

    @Scheduled(cron = "0 0 * * * *", zoneId = "UTC")
    open fun hourly() {
        run()
    }

    /** Returns the number of listings changed, or null when another instance holds the lock. */
    fun run(): Int? =
        tx.executeWrite { status ->
            val conn = status.connection
            if (tryJobLock(conn, DEMAND_LOCK)) {
                demand.update(conn, clock.instant()).also { log.info("DemandJob: {} listings changed", it) }
            } else {
                null
            }
        }
}

const val DEMAND_LOCK = "demand"
