package com.fightexchange.admin

import com.fightexchange.ingest.ADMIN_SOURCE
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.util.UUID

/** What happened to an event an admin submitted: the final ingest status and an open review item, if any. */
data class AdminOutcome(
    val key: String,
    val ingestEventId: Long,
    val status: String,
    val reviewItemId: Long?,
)

/** Admin forms are ordinary ingest events with `source = ADMIN`, processed synchronously. */
@Singleton
class AdminIngest(
    private val service: IngestService,
    private val processor: IngestProcessor,
    private val tx: TransactionOperations<Connection>,
) {
    fun newKey(): String = "admin-${UUID.randomUUID()}"

    fun run(
        payload: IngestPayload,
        key: String = newKey(),
    ): AdminOutcome {
        val submitted = service.submit(ADMIN_SOURCE, key, payload)
        processor.processPending()
        return tx.executeRead { status ->
            val conn = status.connection
            val state =
                conn.prepareStatement("SELECT status FROM ingest_event WHERE id = ?").use { ps ->
                    ps.setLong(1, submitted.id)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            val review =
                conn
                    .prepareStatement(
                        "SELECT id FROM review_item WHERE ingest_event_id = ? AND status = 'OPEN' " +
                            "ORDER BY id DESC LIMIT 1",
                    ).use { ps ->
                        ps.setLong(1, submitted.id)
                        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                    }
            AdminOutcome(key, submitted.id, state, review)
        }
    }
}
