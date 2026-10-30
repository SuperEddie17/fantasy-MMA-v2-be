package com.fightexchange.ingest

import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet

/** Outcome of handling one event; `NEEDS_REVIEW` is for events that wait for an admin decision. */
enum class HandlerOutcome { APPLIED, NEEDS_REVIEW, REJECTED }

/** Applies one event type to the sport data. Runs inside the processor's transaction ([conn]). */
interface IngestHandler {
    val type: IngestEventType

    fun handle(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload,
    ): HandlerOutcome
}

@Singleton
class IngestProcessor(
    private val tx: TransactionOperations<Connection>,
    private val ingestService: IngestService,
    handlers: List<IngestHandler>,
) {
    private val handlersByType = handlers.associateBy { it.type }

    /**
     * Processes `NEW` events in order of receipt, each in its own transaction. Events whose type has no
     * handler stay `NEW`. A handler or parsing failure rolls back that event only and marks it `REJECTED`.
     * Returns the number of events that left the `NEW` state.
     */
    fun processPending(): Int {
        var processed = 0
        val skipped = mutableSetOf<Long>()
        while (true) {
            val next = nextNew(skipped) ?: return processed
            if (handlersByType[next.type] == null) {
                skipped += next.id
                continue
            }
            processOne(next)
            processed++
        }
    }

    @Suppress("TooGenericExceptionCaught") // any handler failure must reject only that event
    private fun processOne(event: StoredIngestEvent) {
        val outcome =
            try {
                // An exception thrown by the handler rolls back this event's transaction only.
                tx.executeWrite { status ->
                    val conn = status.connection
                    val result = handlersByType.getValue(event.type).handle(conn, event, ingestService.parse(event))
                    setStatus(conn, event.id, result.toStatus())
                    result
                }
            } catch (e: Exception) {
                log.warn("Ingest event {} ({}) rejected: {}", event.id, event.type.wireName, e.message)
                tx.executeWrite { status -> setStatus(status.connection, event.id, IngestStatus.REJECTED) }
                HandlerOutcome.REJECTED
            }
        log.debug("Ingest event {} -> {}", event.id, outcome)
    }

    private fun HandlerOutcome.toStatus() =
        when (this) {
            HandlerOutcome.APPLIED -> IngestStatus.APPLIED
            HandlerOutcome.NEEDS_REVIEW -> IngestStatus.NEEDS_REVIEW
            HandlerOutcome.REJECTED -> IngestStatus.REJECTED
        }

    private fun setStatus(
        conn: Connection,
        id: Long,
        status: IngestStatus,
    ) {
        conn
            .prepareStatement(
                "UPDATE ingest_event SET status = ?, " +
                    "applied_at = CASE WHEN ? = 'APPLIED' THEN now() ELSE applied_at END WHERE id = ?",
            ).use { ps ->
                ps.setString(1, status.name)
                ps.setString(2, status.name)
                ps.setLong(3, id)
                ps.executeUpdate()
            }
    }

    private fun nextNew(skipped: Set<Long>): StoredIngestEvent? =
        tx.executeRead { status -> query(status.connection, skipped) }

    private fun query(
        conn: Connection,
        skipped: Set<Long>,
    ): StoredIngestEvent? {
        val ps = conn.prepareStatement(SELECT_NEXT_NEW)
        ps.use {
            it.setArray(1, conn.createArrayOf("bigint", skipped.toTypedArray()))
            val rs = it.executeQuery()
            return rs.use { r -> if (r.next()) r.toStoredEvent() else null }
        }
    }

    private fun ResultSet.toStoredEvent() =
        StoredIngestEvent(
            id = getLong(1),
            source = getString(2),
            externalKey = getString(3),
            type = IngestEventType.fromWireName(getString(4)),
            payloadJson = getString(5),
            status = IngestStatus.valueOf(getString(6)),
        )

    private companion object {
        val log = LoggerFactory.getLogger(IngestProcessor::class.java)
        const val SELECT_NEXT_NEW =
            "SELECT id, source, external_key, type, payload::text, status FROM ingest_event " +
                "WHERE status = 'NEW' AND id <> ALL (?) ORDER BY id LIMIT 1"
    }
}
