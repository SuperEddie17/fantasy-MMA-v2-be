package com.fightexchange.ingest

import com.fightexchange.market.tryJobLock
import io.micronaut.context.annotation.Value
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Hourly check for confirmed bouts whose odds have vanished: a bout of an upcoming event whose newest odds
 * snapshot is at least `ingest.odds.missing-hours` (48) old becomes `NEEDS_REVIEW`. Nothing happens to the price;
 * a drop follows only when an admin confirms a cancellation. New odds put the bout back to `CONFIRMED`.
 */
@Singleton
open class MissingOddsJob(
    private val tx: TransactionOperations<Connection>,
    private val clock: Clock,
    @Value("\${ingest.odds.missing-hours:48}") private val missingHours: Long,
) {
    private val log = LoggerFactory.getLogger(MissingOddsJob::class.java)

    @Scheduled(cron = "0 10 * * * *", zoneId = "UTC")
    open fun check() {
        flagMissing(clock.instant())
    }

    /** Returns the number of bouts flagged, or null when another instance holds the lock. */
    fun flagMissing(now: Instant): Int? =
        tx.executeWrite { status ->
            val conn = status.connection
            if (!tryJobLock(conn, LOCK)) return@executeWrite null
            conn
                .prepareStatement(
                    "UPDATE bout SET status = 'NEEDS_REVIEW' WHERE status = 'CONFIRMED' " +
                        "AND event_id IN (SELECT id FROM event WHERE status = 'SCHEDULED' AND starts_at > ?) " +
                        "AND (SELECT max(taken_at) FROM odds_snapshot o WHERE o.bout_id = bout.id) <= ?",
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp.from(now))
                    ps.setTimestamp(2, Timestamp.from(now.minus(Duration.ofHours(missingHours))))
                    ps.executeUpdate()
                }.also {
                    if (it > 0) log.warn("MissingOddsJob: {} bouts without odds for {} h need review", it, missingHours)
                }
        }

    private companion object {
        const val LOCK = "missing-odds"
    }
}
