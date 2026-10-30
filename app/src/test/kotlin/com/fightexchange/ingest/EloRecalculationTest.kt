package com.fightexchange.ingest

import com.fightexchange.PostgresTest
import com.fightexchange.rules.BoutOutcome
import com.fightexchange.rules.Elo
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant

@MicronautTest(transactional = false)
class EloRecalculationTest : PostgresTest() {
    @Inject lateinit var service: IngestService

    @Inject lateinit var processor: IngestProcessor

    @Inject lateinit var tx: TransactionOperations<Connection>

    private var seq = 0

    @BeforeEach
    @AfterEach
    fun clean() {
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "DELETE FROM bout_bonus; DELETE FROM weight_miss; DELETE FROM bout_result; " +
                        "DELETE FROM elo_rating; DELETE FROM bout; DELETE FROM event; " +
                        "DELETE FROM fighter_external_id; DELETE FROM fighter; " +
                        "DELETE FROM review_item; DELETE FROM ingest_event",
                )
            }
        }
    }

    private fun send(payload: IngestPayload) {
        service.submit(ADMIN_SOURCE, "k${seq++}", payload)
        processor.processPending()
    }

    private fun rating(
        fighter: String,
        bout: String,
    ): Double =
        tx.executeRead { st ->
            st.connection
                .prepareStatement(
                    "SELECT r.rating_after FROM elo_rating r " +
                        "JOIN fighter_external_id x ON x.fighter_id = r.fighter_id AND x.external_id = ? " +
                        "JOIN bout b ON b.id = r.bout_id WHERE b.external_id = ?",
                ).use { ps ->
                    ps.setString(1, fighter)
                    ps.setString(2, bout)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getDouble(1) else Double.NaN }
                }
        }

    private fun ref(id: String) = IngestPayload.FighterRef(externalId = id)

    private fun result(
        bout: String,
        winner: String?,
        method: String = "DEC_U",
    ) = IngestPayload.BoutResult(
        bout,
        if (winner == null) "NO_CONTEST" else "WIN",
        winner?.let { ref(it) },
        method,
    )

    /** f1 fights f2 (e1/b1), then f1 fights f3 (e2/b2), then f3 fights f4 (e3/b3). */
    private fun setup() {
        listOf("f1", "f2", "f3", "f4").forEach {
            send(IngestPayload.FighterUpsert(it, "Fighter $it", "M", "UFC", "Lightweight"))
        }
        val dates = mapOf("e1" to "2026-01-10", "e2" to "2026-02-10", "e3" to "2026-03-10")
        dates.forEach { (id, day) ->
            send(IngestPayload.EventUpsert(id, "UFC", "UFC $id", Instant.parse("${day}T18:00:00Z")))
        }
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2")))
        send(IngestPayload.BoutAnnounced("e2", "b2", ref("f1"), ref("f3")))
        send(IngestPayload.BoutAnnounced("e3", "b3", ref("f3"), ref("f4")))
    }

    @Test
    fun `result rates both fighters, finishes weigh more`() {
        setup()
        send(result("b1", "f1", "KO_TKO"))
        val expected = Elo.update(1500.0, 1500.0, BoutOutcome.A_WIN, finish = true)
        assertEquals(expected.a, rating("f1", "b1"), 1e-9)
        assertEquals(expected.b, rating("f2", "b1"), 1e-9)
    }

    @Test
    fun `no contest leaves ratings untouched`() {
        setup()
        send(result("b1", null, "NC"))
        assertEquals(
            "0",
            tx.executeRead { st ->
                st.connection.createStatement().use { s ->
                    s.executeQuery("SELECT count(*) FROM elo_rating").use {
                        it.next()
                        it.getString(1)
                    }
                }
            },
        )
    }

    @Test
    fun `corrected result recalculates later bouts of both fighters and their opponents`() {
        setup()
        send(result("b1", "f1"))
        send(result("b2", "f1"))
        send(result("b3", "f3"))
        send(result("b1", "f2"))

        val b1 = Elo.update(1500.0, 1500.0, BoutOutcome.B_WIN, finish = false)
        val b2 = Elo.update(b1.a, 1500.0, BoutOutcome.A_WIN, finish = false)
        val b3 = Elo.update(b2.b, 1500.0, BoutOutcome.A_WIN, finish = false)
        assertEquals(b1.b, rating("f2", "b1"), 1e-9)
        assertEquals(b2.a, rating("f1", "b2"), 1e-9)
        assertEquals(b3.a, rating("f3", "b3"), 1e-9)
        assertEquals(
            "6",
            tx.executeRead { st ->
                st.connection.createStatement().use { s ->
                    s.executeQuery("SELECT count(*) FROM elo_rating").use {
                        it.next()
                        it.getString(1)
                    }
                }
            },
        )
    }
}
