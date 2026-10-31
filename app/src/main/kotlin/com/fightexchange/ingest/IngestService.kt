package com.fightexchange.ingest

import io.micronaut.serde.ObjectMapper
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Statement

/** An ingest row as stored in `ingest_event`. */
data class StoredIngestEvent(
    val id: Long,
    val source: String,
    val externalKey: String,
    val type: IngestEventType,
    val payloadJson: String,
    val status: IngestStatus,
)

data class SubmitResult(
    val id: Long,
    val status: IngestStatus,
)

@Singleton
class IngestService(
    private val tx: TransactionOperations<Connection>,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Stores the event as `NEW`. If an original event with the same (source, externalKey) already exists,
     * the new one is stored with status `DUPLICATE` and is never processed.
     */
    fun submit(
        source: String,
        externalKey: String,
        payload: IngestPayload,
    ): SubmitResult {
        require(source.isNotBlank()) { "source must not be blank" }
        require(externalKey.isNotBlank()) { "externalKey must not be blank" }
        val json = objectMapper.writeValueAsString(payload)
        return tx.executeWrite { status ->
            val conn = status.connection
            // The partial unique index on non-duplicates makes concurrent submits of one key race-free.
            val inserted = insert(conn, source, externalKey, payload.eventType, json, "NEW", onConflictDoNothing = true)
            if (inserted != null) {
                SubmitResult(inserted, IngestStatus.NEW)
            } else {
                val duplicate =
                    insert(conn, source, externalKey, payload.eventType, json, "DUPLICATE", onConflictDoNothing = false)
                SubmitResult(checkNotNull(duplicate), IngestStatus.DUPLICATE)
            }
        }
    }

    /** True when an original (non-duplicate) event with this key is stored; lets feeds skip unchanged data. */
    fun exists(
        source: String,
        externalKey: String,
    ): Boolean =
        tx.executeRead { status ->
            status.connection
                .prepareStatement(
                    "SELECT 1 FROM ingest_event WHERE source = ? AND external_key = ? AND status <> 'DUPLICATE'",
                ).use { ps ->
                    ps.setString(1, source)
                    ps.setString(2, externalKey)
                    ps.executeQuery().use { it.next() }
                }
        }

    private fun insert(
        conn: Connection,
        source: String,
        externalKey: String,
        type: IngestEventType,
        json: String,
        status: String,
        onConflictDoNothing: Boolean,
    ): Long? {
        val conflict = if (onConflictDoNothing) ON_CONFLICT_ORIGINAL else ""
        val sql = "$INSERT$conflict"
        conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
            ps.setString(1, source)
            ps.setString(2, externalKey)
            ps.setString(3, type.wireName)
            ps.setString(4, json)
            ps.setString(5, status)
            if (ps.executeUpdate() == 0) return null
            ps.generatedKeys.use { keys ->
                keys.next()
                return keys.getLong("id")
            }
        }
    }

    fun parse(event: StoredIngestEvent): IngestPayload =
        objectMapper.readValue(
            event.payloadJson,
            IngestPayload::class.java,
        )

    private companion object {
        const val INSERT =
            "INSERT INTO ingest_event (source, external_key, type, payload, status) VALUES (?, ?, ?, ?::jsonb, ?)"
        const val ON_CONFLICT_ORIGINAL = " ON CONFLICT (source, external_key) WHERE status <> 'DUPLICATE' DO NOTHING"
    }
}
