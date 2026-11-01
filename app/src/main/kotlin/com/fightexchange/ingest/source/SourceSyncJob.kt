package com.fightexchange.ingest.source

import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton

/**
 * Nightly full pull of every registered source (03:30 UTC). It is the safety net behind the webhook: whatever a
 * missed delivery left out is caught up here. Failures are recorded by [SourceSync] and never thrown.
 */
@Singleton
open class SourceSyncJob(
    private val sync: SourceSync,
) {
    @Scheduled(cron = "0 30 3 * * *", zoneId = "UTC")
    open fun run() {
        syncAll()
    }

    fun syncAll(): Map<String, SyncOutcome> = sync.sources.sorted().associateWith { sync.sync(it) }
}
