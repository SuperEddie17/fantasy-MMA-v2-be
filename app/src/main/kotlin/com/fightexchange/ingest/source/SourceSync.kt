package com.fightexchange.ingest.source

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

sealed interface SyncOutcome {
    data class Done(
        val result: SyncResult,
    ) : SyncOutcome

    data class Failed(
        val message: String,
    ) : SyncOutcome

    data object UnknownSource : SyncOutcome
}

/** Runs source adapters and keeps `source_status` current; the one entry point for scheduled and manual syncs. */
@Singleton
class SourceSync(
    adapters: List<SourceAdapter>,
    private val status: SourceStatusService,
) {
    private val log = LoggerFactory.getLogger(SourceSync::class.java)
    private val bySource = adapters.associateBy { it.source }

    val sources: Set<String> get() = bySource.keys

    fun sync(source: String): SyncOutcome {
        val adapter = bySource[source] ?: return SyncOutcome.UnknownSource
        return try {
            val result = adapter.sync()
            status.recordSuccess(source, result.creditsLeft)
            SyncOutcome.Done(result)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            val message = e.message ?: e.javaClass.simpleName
            log.warn("Sync of source {} failed: {}", source, message)
            status.recordError(source, message)
            SyncOutcome.Failed(message)
        }
    }
}
