package com.fightexchange.ingest.source

import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Instant

/**
 * Hourly tick that polls The Odds API only when [OddsPollSchedule] says it is due and credits are not running out
 * (`ingest.sources.min-credits`); the alert for low credits is [SourceAlertJob]'s. The manual sync ignores both.
 */
@Singleton
@Requires(property = "ingest.theoddsapi.api-key", pattern = ".+")
open class TheOddsApiPollJob(
    private val sync: SourceSync,
    private val status: SourceStatusService,
    private val tx: TransactionOperations<Connection>,
    private val clock: Clock,
    @Value("\${ingest.sources.min-credits:500}") private val minCredits: Int,
) {
    private val log = LoggerFactory.getLogger(TheOddsApiPollJob::class.java)

    @Scheduled(cron = "0 20 * * * *", zoneId = "UTC")
    open fun tick() {
        poll()
    }

    /** Returns the outcome, or `null` when nothing was due. */
    fun poll(): SyncOutcome? {
        val now = clock.instant()
        val current = status.list().firstOrNull { it.source == THE_ODDS_API_SOURCE }
        if (current?.creditsLeft != null && current.creditsLeft <= minCredits) {
            log.warn("The Odds API polling paused: {} credits left", current.creditsLeft)
            return null
        }
        if (!OddsPollSchedule.due(now, current?.lastSuccessAt, nextEventStart(now))) return null
        return sync.sync(THE_ODDS_API_SOURCE)
    }

    /** Start of the nearest event that has not finished (a card runs for hours after its start). */
    private fun nextEventStart(now: Instant): Instant? =
        tx.executeRead { st ->
            st.connection
                .prepareStatement(
                    "SELECT min(starts_at) FROM event WHERE status = 'SCHEDULED' AND starts_at > ?",
                ).use { ps ->
                    ps.setTimestamp(1, java.sql.Timestamp.from(now.minus(java.time.Duration.ofHours(CARD_HOURS))))
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1)?.toInstant() else null }
                }
        }

    private companion object {
        const val CARD_HOURS = 12L
    }
}
