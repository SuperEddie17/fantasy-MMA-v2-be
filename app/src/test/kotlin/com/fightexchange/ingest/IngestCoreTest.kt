package com.fightexchange.ingest

import com.fightexchange.PostgresTest
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection

/** Test handler: records handled event ids, fails for a poisoned external id. */
class RecordingHandler : IngestHandler {
    val handled = mutableListOf<Long>()
    override val type = IngestEventType.FIGHTER_UPSERT

    override fun handle(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload,
    ): HandlerOutcome {
        payload as IngestPayload.FighterUpsert
        conn.createStatement().use {
            it.execute("INSERT INTO review_item (ingest_event_id, reason) VALUES (${event.id}, 'FIGHTER_MATCH')")
        }
        check(payload.externalId != "poison") { "poisoned" }
        handled += event.id
        return HandlerOutcome.APPLIED
    }
}

@MicronautTest(transactional = false)
class IngestCoreTest : PostgresTest() {
    @Inject lateinit var service: IngestService

    @Inject lateinit var tx: TransactionOperations<Connection>

    // Built by hand so the real handlers of the application do not interfere with the recording one.
    private val handler = RecordingHandler()
    private lateinit var processor: IngestProcessor
    private lateinit var emptyProcessor: IngestProcessor

    @BeforeEach
    fun clean() {
        processor = IngestProcessor(tx, service, listOf(handler))
        emptyProcessor = IngestProcessor(tx, service, emptyList())
        handler.handled.clear()
        tx.executeWrite { it.connection.createStatement().execute("DELETE FROM review_item; DELETE FROM ingest_event") }
    }

    private fun fighter(id: String) =
        IngestPayload.FighterUpsert(
            externalId = id,
            name = "Name $id",
            sex = "M",
            organization = "UFC",
        )

    private fun count(sql: String): Int =
        tx.executeRead { st ->
            val c = st.connection
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun `same source and key twice is stored once as NEW and once as DUPLICATE`() {
        val first = service.submit("ADMIN", "k1", fighter("1"))
        val second = service.submit("ADMIN", "k1", fighter("1"))
        assertEquals(IngestStatus.NEW, first.status)
        assertEquals(IngestStatus.DUPLICATE, second.status)
        processor.processPending()
        assertEquals(listOf(first.id), handler.handled)
    }

    @Test
    fun `events are processed in order of receipt and marked APPLIED`() {
        val ids = (1..3).map { service.submit("ADMIN", "k$it", fighter("$it")).id }
        assertEquals(3, processor.processPending())
        assertEquals(ids, handler.handled)
        assertEquals(3, count("SELECT count(*) FROM ingest_event WHERE status = 'APPLIED' AND applied_at IS NOT NULL"))
    }

    @Test
    fun `failing event is rolled back and rejected, later events still run`() {
        service.submit("ADMIN", "bad", fighter("poison"))
        val ok = service.submit("ADMIN", "ok", fighter("2"))
        processor.processPending()
        assertEquals(listOf(ok.id), handler.handled)
        assertEquals(1, count("SELECT count(*) FROM ingest_event WHERE status = 'REJECTED'"))
        assertEquals(1, count("SELECT count(*) FROM review_item"))
    }

    @Test
    fun `events without a handler stay NEW`() {
        service.submit(
            "ADMIN",
            "r",
            IngestPayload.OddsSnapshot("b", java.time.Instant.parse("2026-01-01T00:00:00Z"), 1.5, 2.5),
        )
        assertEquals(0, emptyProcessor.processPending())
        assertEquals(1, count("SELECT count(*) FROM ingest_event WHERE status = 'NEW'"))
    }

    @Test
    fun `payload round-trips with type discriminator and schema version`() {
        val id = service.submit("ADMIN", "rt", fighter("9")).id
        val json =
            tx.executeRead { st ->
                val c = st.connection
                c.createStatement().use { s ->
                    s.executeQuery("SELECT payload::text FROM ingest_event WHERE id = $id").use {
                        it.next()
                        it.getString(1)
                    }
                }
            }
        assertEquals(
            true,
            json.contains("\"type\": \"fighter.upsert\"") || json.contains("\"type\":\"fighter.upsert\""),
            json,
        )
        assertEquals(true, json.contains("\"schemaVersion\""), json)
    }
}
