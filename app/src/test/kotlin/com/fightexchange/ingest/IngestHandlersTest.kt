package com.fightexchange.ingest

import com.fightexchange.PostgresTest
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
class IngestHandlersTest : PostgresTest() {
    @Inject lateinit var service: IngestService

    @Inject lateinit var processor: IngestProcessor

    @Inject lateinit var tx: TransactionOperations<Connection>

    private val starts = Instant.parse("2026-12-05T18:00:00Z")
    private var seq = 0

    @BeforeEach
    @AfterEach
    fun clean() {
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "DELETE FROM odds_snapshot; DELETE FROM bout_bonus; DELETE FROM weight_miss; " +
                        "DELETE FROM bout_result; DELETE FROM elo_rating; DELETE FROM bout; DELETE FROM event; " +
                        "DELETE FROM ranking_snapshot; DELETE FROM fighter_external_id; DELETE FROM fighter; " +
                        "DELETE FROM review_item; DELETE FROM ingest_event",
                )
            }
        }
    }

    private fun send(
        payload: IngestPayload,
        source: String = ADMIN_SOURCE,
    ): Long {
        val id = service.submit(source, "k${seq++}", payload).id
        processor.processPending()
        return id
    }

    private fun status(id: Long) = scalar("SELECT status FROM ingest_event WHERE id = $id")

    private fun scalar(sql: String): String? =
        tx.executeRead { st ->
            st.connection.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun fighter(
        id: String,
        name: String,
        division: String? = "Lightweight",
    ) = IngestPayload.FighterUpsert(id, name, "M", "UFC", division)

    private fun ref(id: String) = IngestPayload.FighterRef(externalId = id)

    /** An event with two fighters and one announced bout `b1`. */
    private fun card(source: String = ADMIN_SOURCE) {
        send(fighter("f1", "Jiří Procházka"), source)
        send(fighter("f2", "Khalil Rountree"), source)
        send(IngestPayload.EventUpsert("e1", "UFC", "UFC 999", starts), source)
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2"), "MAIN"), source)
    }

    @Test
    fun `fighter upsert creates once and updates by external id`() {
        send(fighter("f1", "Jiří Procházka", division = null))
        val id = send(fighter("f1", "Jiri Prochazka", division = "Light Heavyweight"))
        assertEquals("APPLIED", status(id))
        assertEquals("1", scalar("SELECT count(*) FROM fighter"))
        assertEquals("Light Heavyweight", scalar("SELECT division FROM fighter"))
        assertEquals("jiri prochazka", scalar("SELECT normalized_name FROM fighter"))
    }

    @Test
    fun `fighter upsert with an ambiguous name needs review`() {
        send(fighter("a", "Dup Fighter", "Lightweight"))
        tx.executeWrite {
            it.connection.createStatement().use { st ->
                st.execute(
                    "INSERT INTO fighter (name, normalized_name, sex, division, organization_id) " +
                        "SELECT 'Dup Fighter', 'dup fighter', 'M', 'Welterweight', id FROM organization WHERE code = 'UFC'",
                )
            }
        }
        val id = send(fighter("c", "Dup Fighter", "Heavyweight"))
        assertEquals("NEEDS_REVIEW", status(id))
        assertEquals("FIGHTER_MATCH", scalar("SELECT reason FROM review_item WHERE ingest_event_id = $id"))
        assertEquals("2", scalar("SELECT count(*) FROM fighter"))
    }

    @Test
    fun `event upsert is idempotent`() {
        send(IngestPayload.EventUpsert("e1", "UFC", "UFC 999", starts))
        send(IngestPayload.EventUpsert("e1", "UFC", "UFC 999 renamed", starts, weighInAt = starts.minusSeconds(86_400)))
        assertEquals("1", scalar("SELECT count(*) FROM event"))
        assertEquals("UFC 999 renamed", scalar("SELECT name FROM event"))
        assertEquals("true", scalar("SELECT (weigh_in_at IS NOT NULL)::text FROM event"))
    }

    @Test
    fun `bout is announced once and a trusted admin sets the admin time`() {
        card()
        val again = send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2")))
        assertEquals("APPLIED", status(again))
        assertEquals("1", scalar("SELECT count(*) FROM bout"))
        assertEquals("MAIN", scalar("SELECT bout_type FROM bout"))
        assertEquals(
            "true",
            scalar("SELECT (announced_at_admin IS NOT NULL AND announced_at_source IS NULL)::text FROM bout"),
        )
    }

    @Test
    fun `bout from a source records source and seen time`() {
        card("SRC")
        assertEquals(
            "true",
            scalar("SELECT (announced_at_seen IS NOT NULL AND announced_at_admin IS NULL)::text FROM bout"),
        )
    }

    @Test
    fun `bout with an unknown fighter needs review and is not created`() {
        send(fighter("f1", "Jiří Procházka"))
        send(IngestPayload.EventUpsert("e1", "UFC", "UFC 999", starts))
        val id = send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("nobody")))
        assertEquals("NEEDS_REVIEW", status(id))
        assertEquals("0", scalar("SELECT count(*) FROM bout"))
    }

    @Test
    fun `bout for an unknown event is rejected`() {
        send(fighter("f1", "A One"))
        send(fighter("f2", "B Two"))
        val id = send(IngestPayload.BoutAnnounced("missing", "b1", ref("f1"), ref("f2")))
        assertEquals("REJECTED", status(id))
    }

    @Test
    fun `bout change swaps the opponent`() {
        card()
        send(fighter("f3", "Replacement Guy"))
        send(IngestPayload.BoutChanged("b1", fighterB = ref("f3"), boutType = "TITLE"))
        assertEquals("TITLE", scalar("SELECT bout_type FROM bout"))
        assertEquals("Replacement Guy", scalar("SELECT name FROM fighter WHERE id = (SELECT fighter_b_id FROM bout)"))
    }

    @Test
    fun `odds snapshot is stored and invalid odds are rejected`() {
        card()
        send(IngestPayload.OddsSnapshot("b1", starts, 1.5, 2.6, closing = true))
        val bad = send(IngestPayload.OddsSnapshot("b1", starts, 1.0, 2.6))
        assertEquals("1", scalar("SELECT count(*) FROM odds_snapshot WHERE is_closing"))
        assertEquals("REJECTED", status(bad))
    }

    private fun result(winner: String = "f1") =
        IngestPayload.BoutResult(
            boutExternalId = "b1",
            outcome = "WIN",
            winner = ref(winner),
            method = "KO_TKO",
            round = 2,
            time = "3:10",
            bonuses = listOf(IngestPayload.Bonus(ref(winner), "PERFORMANCE")),
            weightMisses = listOf(ref("f2")),
        )

    @Test
    fun `admin result is applied immediately with bonuses and weight miss`() {
        card()
        val id = send(result())
        assertEquals("APPLIED", status(id))
        assertEquals("COMPLETED", scalar("SELECT status FROM bout"))
        assertEquals("1", scalar("SELECT version FROM bout_result WHERE is_current"))
        assertEquals("PERFORMANCE", scalar("SELECT type FROM bout_bonus"))
        assertEquals("1", scalar("SELECT count(*) FROM weight_miss"))
    }

    @Test
    fun `corrected result is a new version and replaces bonuses`() {
        card()
        send(result("f1"))
        send(result("f2"))
        assertEquals("2", scalar("SELECT version FROM bout_result WHERE is_current"))
        assertEquals("2", scalar("SELECT count(*) FROM bout_result"))
        assertEquals(
            "Khalil Rountree",
            scalar("SELECT name FROM fighter WHERE id = (SELECT fighter_id FROM bout_bonus)"),
        )
    }

    @Test
    fun `result from an external source waits for confirmation`() {
        card("SRC")
        val id = send(result(), "SRC")
        assertEquals("NEEDS_REVIEW", status(id))
        assertEquals("RESULT_CONFIRM", scalar("SELECT reason FROM review_item WHERE ingest_event_id = $id"))
        assertEquals("0", scalar("SELECT count(*) FROM bout_result"))
        assertEquals("ANNOUNCED", scalar("SELECT status FROM bout"))
    }

    @Test
    fun `result with a winner outside the bout needs review`() {
        card()
        send(fighter("f3", "Someone Else"))
        val id = send(result("f3"))
        assertEquals("NEEDS_REVIEW", status(id))
        assertEquals("0", scalar("SELECT count(*) FROM bout_result"))
    }

    @Test
    fun `cancellation is gated for sources and direct for admin`() {
        card("SRC")
        val held = send(IngestPayload.BoutCancelled("b1", "injury", ref("f2")), "SRC")
        assertEquals("NEEDS_REVIEW", status(held))
        assertEquals("CANCELLATION", scalar("SELECT reason FROM review_item WHERE ingest_event_id = $held"))
        assertEquals("ANNOUNCED", scalar("SELECT status FROM bout"))

        // The same bout cancelled by an admin: the admin source has its own bout namespace, so create it there.
        card()
        val done = send(IngestPayload.BoutCancelled("b1", "injury", ref("f2")))
        assertEquals("APPLIED", status(done))
        assertEquals(
            "injury",
            scalar("SELECT cancel_reason FROM bout WHERE source = 'ADMIN'"),
        )
        assertEquals(
            "Khalil Rountree",
            scalar(
                "SELECT name FROM fighter WHERE id = (SELECT withdrawn_fighter_id FROM bout WHERE source = 'ADMIN')",
            ),
        )
    }

    @Test
    fun `ranking snapshot stores matched fighters and skips unknown ones`() {
        card()
        send(
            IngestPayload.RankingSnapshot(
                "UFC",
                "Lightweight",
                starts,
                listOf(
                    IngestPayload.RankedFighter(ref("f1"), 1),
                    IngestPayload.RankedFighter(ref("f2"), 2),
                    IngestPayload.RankedFighter(ref("ghost"), 3),
                ),
            ),
        )
        assertEquals("2", scalar("SELECT count(*) FROM ranking_snapshot"))
    }
}
