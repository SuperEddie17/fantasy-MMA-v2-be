package com.fightexchange.ranking

import com.fightexchange.market.tryJobLock
import com.fightexchange.payout.CaptainService
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

const val NET_WORTH_LOCK = "net-worth"
const val LEADERBOARD_LOCK = "leaderboard"
const val MONTH_CLOSE_LOCK = "month-close"
const val CAPTAIN_LOCK = "captain-lock"

/**
 * Scheduled game jobs (plan.md, F4). Like the market jobs each run holds a Postgres advisory lock for its
 * transaction, so with several instances only one runs it, and each is idempotent. The methods take the time
 * explicitly and return null when another instance holds the lock.
 */
@Singleton
open class RankingJobs(
    private val tx: TransactionOperations<Connection>,
    private val netWorth: NetWorthService,
    private val leaderboards: LeaderboardService,
    private val captains: CaptainService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(RankingJobs::class.java)

    @Scheduled(cron = "0 15 0 * * *", zoneId = "UTC")
    open fun netWorthJob() {
        runNetWorth(clock.instant())
    }

    @Scheduled(cron = "0 20 0 * * *", zoneId = "UTC")
    open fun leaderboardJob() {
        runLeaderboard(clock.instant())
    }

    @Scheduled(cron = "0 0 1 1 * *", zoneId = "UTC")
    open fun monthCloseJob() {
        runMonthClose(clock.instant())
    }

    @Scheduled(cron = "0 */5 * * * *", zoneId = "UTC")
    open fun captainLockJob() {
        runCaptainLock(clock.instant())
    }

    /** Snapshots the net worth of the day [now] falls on; returns the number of rows. */
    fun runNetWorth(now: Instant): Int? =
        locked(NET_WORTH_LOCK) { conn ->
            netWorth.snapshot(conn, day(now), now).also { log.info("NetWorthJob: {} snapshots", it) }
        }

    /** Rebuilds the season ranking and the current month's sprint of every active season. */
    fun runLeaderboard(now: Instant): Int? =
        locked(LEADERBOARD_LOCK) { conn ->
            val today = day(now)
            val month = YearMonth.from(today)
            leaderboards
                .activeSeasons(conn, now)
                .sumOf {
                    leaderboards.rebuildSeason(conn, it, today) +
                        leaderboards.rebuildMonth(conn, it, month, today, false)
                }.also { log.info("LeaderboardJob: {} rows", it) }
        }

    /** Closes the sprint of the month before the one [now] falls in; the rank 1 rows are the winners. */
    fun runMonthClose(now: Instant): Int? =
        locked(MONTH_CLOSE_LOCK) { conn ->
            val today = day(now)
            val month = YearMonth.from(today).minusMonths(1)
            leaderboards
                .seasonsOverlapping(conn, month)
                .sumOf { leaderboards.rebuildMonth(conn, it, month, today, true) }
                .also { log.info("MonthCloseJob: {} rows for {}", it, month) }
        }

    fun runCaptainLock(now: Instant): Int? =
        locked(CAPTAIN_LOCK) { captains.lockDue(now) }

    private fun day(now: Instant): LocalDate = now.atZone(ZoneOffset.UTC).toLocalDate()

    private fun <T : Any> locked(
        name: String,
        block: (Connection) -> T,
    ): T? =
        tx.executeWrite { status ->
            val conn = status.connection
            if (tryJobLock(conn, name)) block(conn) else null
        }
}
