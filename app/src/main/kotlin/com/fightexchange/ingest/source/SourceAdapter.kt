package com.fightexchange.ingest.source

/** Outcome of one [SourceAdapter.sync]: how many events were handed to ingest and, if the API says so, credits left. */
data class SyncResult(
    val submitted: Int,
    val creditsLeft: Int? = null,
)

/**
 * A data source that produces only ingest events (plan.md F5). Implementations are beans; [SourceSync] runs them
 * and records the outcome in `source_status`.
 */
interface SourceAdapter {
    /** The `source` value used in `ingest_event`, e.g. `UFCALENDAR`. */
    val source: String

    /** Pulls the source's current data and submits it through `IngestService`. Throws on a failed fetch. */
    fun sync(): SyncResult
}
