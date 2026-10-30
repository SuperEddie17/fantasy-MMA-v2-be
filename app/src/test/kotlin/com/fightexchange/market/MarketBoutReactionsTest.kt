package com.fightexchange.market

import com.fightexchange.PostgresTest
import com.fightexchange.ingest.ADMIN_SOURCE
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
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
class MarketBoutReactionsTest : PostgresTest() {
    @Inject lateinit var service: IngestService

    @Inject lateinit var processor: IngestProcessor

    @Inject lateinit var tx: TransactionOperations<Connection>

    private var seq = 0
    private val starts = Instant.parse("2026-12-05T18:00:00Z")

    @BeforeEach
    @AfterEach
    fun clean() {
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "DELETE FROM bout_price_bump; " +
                        "DELETE FROM price_event; DELETE FROM price_daily; " +
                        "DELETE FROM market_listing; DELETE FROM bout_result; DELETE FROM elo_rating; " +
                        "DELETE FROM bout; DELETE FROM event; DELETE FROM fighter_external_id; " +
                        "DELETE FROM fighter; DELETE FROM review_item; DELETE FROM ingest_event",
                )
            }
        }
    }

    private fun send(payload: IngestPayload) {
        service.submit(ADMIN_SOURCE, "k${seq++}", payload)
        processor.processPending()
    }

    private fun ref(id: String) = IngestPayload.FighterRef(externalId = id)

    private fun scalar(sql: String): String? =
        tx.executeRead { st ->
            st.connection.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun multiplier(external: String) =
        scalar(
            "SELECT event_multiplier FROM market_listing l JOIN fighter_external_id x ON x.fighter_id = l.fighter_id " +
                "WHERE x.external_id = '$external'",
        )!!.toDouble()

    private fun events(external: String) =
        scalar(
            "SELECT string_agg(kind, ',' ORDER BY p.id) FROM price_event p JOIN fighter_external_id x " +
                "ON x.fighter_id = p.fighter_id WHERE x.external_id = '$external' AND kind <> 'ELO'",
        )

    /** Two debutants with equal Elo (no stronger-opponent bump) and an event. */
    private fun setup() {
        listOf(
            "f1",
            "f2",
            "f3",
        ).forEach { send(IngestPayload.FighterUpsert(it, "Fighter $it", "M", "UFC", "Lightweight")) }
        send(IngestPayload.EventUpsert("e1", "UFC", "UFC 999", starts))
    }

    @Test
    fun `announcement bumps both fighters by the bout type`() {
        setup()
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2"), "MAIN"))
        assertEquals(1.05, multiplier("f1"), 1e-9)
        assertEquals(1.05, multiplier("f2"), 1e-9)
        assertEquals("ANNOUNCED", events("f1"))
    }

    @Test
    fun `stronger opponent adds a bonus logged separately`() {
        setup()
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "WITH b AS (INSERT INTO bout (event_id, fighter_a_id, fighter_b_id, status, source, external_id) " +
                        "SELECT (SELECT id FROM event LIMIT 1), a.fighter_id, c.fighter_id, " +
                        "'COMPLETED', 'ADMIN', 'old' " +
                        "FROM fighter_external_id a, fighter_external_id c " +
                        "WHERE a.external_id = 'f2' AND c.external_id = 'f3' RETURNING id, fighter_a_id) " +
                        "INSERT INTO elo_rating (fighter_id, bout_id, rating_before, rating_after, rated_at) " +
                        "SELECT fighter_a_id, id, 1500, 1750, now() FROM b",
                )
            }
        }
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2"), "NORMAL"))
        assertEquals(1.02 * 1.03, multiplier("f1"), 1e-5)
        assertEquals("ANNOUNCED,OPPONENT_BONUS", events("f1"))
        assertEquals(1.02, multiplier("f2"), 1e-9)
    }

    @Test
    fun `changing the bout type or opponent moves only the difference`() {
        setup()
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2"), "NORMAL"))
        send(IngestPayload.BoutChanged("b1", boutType = "TITLE"))
        assertEquals(1.10, multiplier("f1"), 1e-5)
        send(IngestPayload.BoutChanged("b1", fighterB = ref("f3")))
        assertEquals(1.0, multiplier("f2"), 1e-5)
        assertEquals(1.10, multiplier("f3"), 1e-5)
        assertEquals(1.10, multiplier("f1"), 1e-5)
    }

    @Test
    fun `cancellation reverts the bump and drops the withdrawn fighter`() {
        setup()
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2"), "MAIN"))
        send(IngestPayload.BoutCancelled("b1", reason = "Injury", withdrawnFighter = ref("f2")))
        assertEquals(1.0, multiplier("f1"), 1e-5)
        assertEquals(0.90, multiplier("f2"), 1e-5)
        assertEquals("ANNOUNCED,ANNOUNCED,CANCELLED", events("f2"))
        assertEquals("0", scalar("SELECT count(*) FROM bout_price_bump"))
    }

    @Test
    fun `cancellation without an injury drops by the minimum`() {
        setup()
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2")))
        send(IngestPayload.BoutCancelled("b1", withdrawnFighter = ref("f1")))
        assertEquals(0.95, multiplier("f1"), 1e-5)
    }
}
