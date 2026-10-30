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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Clock
import java.time.Instant

@MicronautTest(transactional = false)
class MarketReplayTest : PostgresTest() {
    @Inject lateinit var service: IngestService

    @Inject lateinit var processor: IngestProcessor

    @Inject lateinit var jobs: MarketJobs

    @Inject lateinit var replay: MarketReplay

    @Inject lateinit var clock: Clock

    @Inject lateinit var tx: TransactionOperations<Connection>

    private var seq = 0

    override fun getProperties(): Map<String, String> = super.getProperties() + ("test.clock" to "mutable")

    @BeforeEach
    @AfterEach
    fun clean() {
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "DELETE FROM ranking_snapshot; DELETE FROM odds_snapshot; DELETE FROM bout_price_drift; " +
                        "DELETE FROM bout_price_bump; DELETE FROM price_event; DELETE FROM price_daily; " +
                        "DELETE FROM market_listing; DELETE FROM bout_result; DELETE FROM elo_rating; " +
                        "DELETE FROM bout; DELETE FROM event; DELETE FROM fighter_external_id; DELETE FROM fighter; " +
                        "DELETE FROM review_item; DELETE FROM ingest_event",
                )
            }
        }
    }

    private fun at(text: String) = Instant.parse("${text}T00:00:00Z")

    private fun now(text: String) {
        (clock as MutableClock).now = at(text)
    }

    private fun send(payload: IngestPayload) {
        service.submit(ADMIN_SOURCE, "k${seq++}", payload)
        processor.processPending()
    }

    private fun ref(id: String) = IngestPayload.FighterRef(externalId = id)

    private fun query(sql: String): List<List<Any?>> =
        tx.executeRead { st ->
            st.connection.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) }) }
                }
            }
        }

    private fun result(
        bout: String,
        winner: String,
    ) = IngestPayload.BoutResult(bout, "WIN", ref(winner), "KO_TKO", 1, boutSource = ADMIN_SOURCE)

    /**
     * A small history: IPOs, two cards, a cancelled bout (injury), fight-week odds, a ranking move, and a fighter
     * who then sits idle until the inactivity decay starts. Returns the day the live market was brought up to.
     */
    private fun play(): String {
        now("2025-01-01")
        "ABCDEF".forEach { send(IngestPayload.FighterUpsert(it.toString(), "Fighter $it", "M", "UFC", "Lightweight")) }
        // upserts are dated by the clock, not by the database
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute("UPDATE fighter SET created_at = '2025-01-01'")
            }
        }
        send(IngestPayload.EventUpsert("e1", "UFC", "UFC 1", at("2025-03-01")))
        send(IngestPayload.EventUpsert("e2", "UFC", "UFC 2", at("2025-05-01")))
        now("2025-01-20")
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("A"), ref("B"), "MAIN"))
        send(IngestPayload.BoutAnnounced("e1", "b2", ref("C"), ref("D"), "TITLE"))
        now("2025-02-10")
        send(IngestPayload.BoutCancelled("b2", "injury", ref("C")))
        now("2025-02-25")
        send(IngestPayload.OddsSnapshot("b1", at("2025-02-25"), 1.5, 2.6, boutSource = ADMIN_SOURCE))
        now("2025-03-01")
        send(result("b1", "A"))
        now("2025-03-20")
        send(IngestPayload.BoutAnnounced("e2", "b3", ref("A"), ref("C")))
        send(
            IngestPayload.RankingSnapshot("UFC", "Lightweight", at("2025-03-20"), listOf(rank("A", 3), rank("B", 7))),
        )
        now("2025-04-10")
        send(
            IngestPayload.RankingSnapshot("UFC", "Lightweight", at("2025-04-10"), listOf(rank("A", 1), rank("B", 9))),
        )
        now("2025-04-26")
        send(IngestPayload.OddsSnapshot("b3", at("2025-04-26"), 1.4, 3.0, boutSource = ADMIN_SOURCE))
        now("2025-05-01")
        send(result("b3", "C"))
        // 2025-01-01 + 183 days = 2025-07-03: E and F start to decay (no fight, no announced bout)
        val end = "2025-09-01"
        now(end)
        jobs.runInactivity(at(end))
        return end
    }

    private fun rank(
        id: String,
        n: Int,
    ) = IngestPayload.RankedFighter(ref(id), n)

    /** Rounding: one catch-up decay and nine daily ones differ by a few units of money. */
    private fun assertSameListings(
        expected: List<List<Any?>>,
        actual: List<List<Any?>>,
    ) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e[0], a[0])
            assertEquals((e[1] as Long).toDouble(), (a[1] as Long).toDouble(), 10.0, "price of ${e[0]}")
            assertEquals(e.drop(2).filterIndexed { i, _ -> i != 0 }, a.drop(2).filterIndexed { i, _ -> i != 0 })
        }
    }

    private fun listings() =
        query(
            "SELECT x.external_id, l.price, l.base_value, l.event_multiplier, l.status, l.last_fight_at " +
                "FROM market_listing l JOIN fighter_external_id x ON x.fighter_id = l.fighter_id ORDER BY 1",
        )

    /** Price events except the inactivity ticks: live they come in one catch-up run, the replay runs every day. */
    private fun events(since: String = "1970-01-01") =
        query(
            "SELECT x.external_id, p.at, p.kind, p.price_before, p.price_after FROM price_event p " +
                "JOIN fighter_external_id x ON x.fighter_id = p.fighter_id " +
                "WHERE p.at >= '$since' AND p.kind <> 'INACTIVITY' " +
                "ORDER BY p.at, x.external_id, p.id",
        )

    @Test
    fun `replaying the whole history rebuilds the live market`() {
        val end = play()
        val live = listings()
        val liveEvents = events()
        assertEquals(6, live.size)
        assertTrue(live.any { it[4] == "ACTIVE" })

        val result = replay.replay(at("2025-01-01"), at(end).plusSeconds(4 * 3600))!!
        assertSameListings(live, listings())
        // same kinds and prices in the same order; only the IPO events carry another time or are not repeated
        assertEquals(
            liveEvents.map { it.drop(2) },
            events().map { it.drop(2) },
        )
        assertTrue(result.dailyRows > 0)
        assertTrue(result.priceEvents > 0)
    }

    @Test
    fun `a replay from a later date keeps the earlier history and rewrites the rest`() {
        val end = play()
        val before = events()
        val live = listings()

        replay.replay(at("2025-04-01"), at(end).plusSeconds(4 * 3600))!!

        assertEquals(before, events())
        assertSameListings(live, listings())
        assertTrue((query("SELECT count(*) FROM price_daily WHERE day >= '2025-04-01'")[0][0] as Long) > 0)
        assertEquals(0L, query("SELECT count(*) FROM price_daily WHERE day < '2025-04-01'")[0][0])
    }

    @Test
    fun `a corrected rating flows into the prices after the replay`() {
        val end = play()
        val a = listings().first { it[0] == "A" }
        // the Elo of A after the second card was corrected
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "UPDATE elo_rating SET rating_after = 1450 WHERE fighter_id = " +
                        "(SELECT fighter_id FROM fighter_external_id WHERE external_id = 'A') AND " +
                        "bout_id = (SELECT id FROM bout WHERE external_id = 'b3')",
                )
            }
        }
        replay.replay(at("2025-04-01"), at(end).plusSeconds(4 * 3600))!!
        val after = listings().first { it[0] == "A" }
        assertTrue((after[1] as Long) < (a[1] as Long), "price of A must follow the corrected Elo")
    }

    @Test
    fun `an idle fighter decays weekly after 26 weeks and the replay keeps that`() {
        val end = play()
        val idle = listings().first { it[0] == "E" }
        val base = idle[2] as Long
        // 2025-07-03 is day 183: first decay; 2025-09-01 is day 243 -> ceil(61 / 7) = 9 decays
        assertEquals(base * Math.pow(0.99, 9.0), (idle[1] as Long).toDouble(), 10.0)
        replay.replay(at("2025-06-01"), at(end).plusSeconds(4 * 3600))!!
        assertEquals((idle[1] as Long).toDouble(), (listings().first { it[0] == "E" }[1] as Long).toDouble(), 10.0)
    }

    @Test
    fun `a running market job blocks the replay`() {
        play()
        val held =
            tx.executeWrite { st ->
                val conn = st.connection
                assertTrue(tryJobLock(conn, INACTIVITY_LOCK))
                // other session: a replay on its own connection cannot get the lock while this transaction lives
                Thread { }.join()
                replayBlocked()
            }
        assertEquals(null, held)
    }

    private fun replayBlocked(): ReplayResult? {
        var result: ReplayResult? = ReplayResult(Instant.EPOCH, Instant.EPOCH, 0, 0, 0, 0)
        val t = Thread { result = replay.replay(at("2025-01-01"), at("2025-02-01")) }
        t.start()
        t.join()
        return result
    }
}
