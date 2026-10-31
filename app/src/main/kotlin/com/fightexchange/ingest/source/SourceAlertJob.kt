package com.fightexchange.ingest.source

import io.micronaut.context.annotation.Value
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Checks source health every 30 minutes and logs each problem as ERROR (outage over 6 h, credits running low).
 * Sending an e-mail needs a mail provider, which the project does not have yet; the log line is the hook for it.
 */
@Singleton
open class SourceAlertJob(
    private val status: SourceStatusService,
    @Value("\${ingest.sources.max-silence-hours:6}") private val maxSilenceHours: Long,
    @Value("\${ingest.sources.min-credits:500}") private val minCredits: Int,
) {
    private val log = LoggerFactory.getLogger(SourceAlertJob::class.java)

    @Scheduled(cron = "0 */30 * * * *", zoneId = "UTC")
    open fun check() {
        alerts().forEach { log.error("Source {}: {}", it.source, it.reason) }
    }

    fun alerts(): List<SourceAlert> = status.alerts(Duration.ofHours(maxSilenceHours), minCredits)
}
