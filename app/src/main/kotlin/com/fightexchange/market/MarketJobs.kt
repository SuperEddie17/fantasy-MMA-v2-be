package com.fightexchange.market

import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Scheduled market jobs. Each run holds a Postgres advisory lock for its transaction, so with several app
 * instances only one runs it, and each is idempotent (see [MarketMaintenance.applyInactivity] and
 * [MarketMaintenance.closeDay]).
 */
@Singleton
open class MarketJobs(
    private val tx: TransactionOperations<Connection>,
    private val maintenance: MarketMaintenance,
) {
    private val log = LoggerFactory.getLogger(MarketJobs::class.java)

    @Scheduled(cron = "0 0 3 * * *", zoneId = "UTC")
    open fun inactivityJob() {
        runInactivity(Instant.now())
    }

    @Scheduled(cron = "0 5 0 * * *", zoneId = "UTC")
    open fun priceDailyJob() {
        closePricesThrough(LocalDate.now(ZoneOffset.UTC).minusDays(1))
    }

    /** Returns the number of listings changed, or null when another instance holds the lock. */
    fun runInactivity(now: Instant): Int? =
        locked(INACTIVITY_LOCK) { conn ->
            maintenance.applyInactivity(conn, now).also { log.info("InactivityJob: {} listings changed", it) }
        }

    /**
     * Closes `price_daily` for every day after the last closed one up to [last] (just [last] when none is closed
     * yet), so a missed night is caught up. Returns the number of rows written, or null when locked elsewhere.
     */
    fun closePricesThrough(last: LocalDate): Int? =
        locked(PRICE_DAILY_LOCK) { conn ->
            val latest =
                conn.createStatement().use { s ->
                    s.executeQuery("SELECT max(day) FROM price_daily").use { rs ->
                        rs.next()
                        rs.getObject(1, LocalDate::class.java)
                    }
                }
            val from = if (latest == null || latest >= last) last else latest.plusDays(1)
            generateSequence(from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(last) }
                .sumOf { maintenance.closeDay(conn, it) }
                .also { log.info("PriceDailyJob: {} rows up to {}", it, last) }
        }

    private fun <T : Any> locked(
        name: String,
        block: (Connection) -> T,
    ): T? =
        tx.executeWrite { status ->
            val conn = status.connection
            if (tryJobLock(conn, name)) block(conn) else null
        }
}

/** Takes the transaction-scoped advisory lock of the market job [name]; false when another transaction holds it. */
fun tryJobLock(
    conn: Connection,
    name: String,
): Boolean =
    conn.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtext(?))").use { ps ->
        ps.setString(1, "market-job:$name")
        ps.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

const val INACTIVITY_LOCK = "inactivity"
const val PRICE_DAILY_LOCK = "price-daily"
