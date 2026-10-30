package com.fightexchange.market

import com.fightexchange.PostgresTest
import com.fightexchange.ingest.ADMIN_SOURCE
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
import com.fightexchange.rules.Pricing
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
class MarketResultReactionsTest : PostgresTest() {
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
                    "DELETE FROM ranking_snapshot; DELETE FROM odds_snapshot; DELETE FROM bout_price_drift; " +
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

    private fun price(external: String) =
        scalar(
            "SELECT price FROM market_listing l JOIN fighter_external_id x ON x.fighter_id = l.fighter_id " +
                "WHERE x.external_id = '$external'",
        )!!.toLong()

    private fun setup() {
        listOf("f1", "f2").forEach { send(IngestPayload.FighterUpsert(it, "Fighter $it", "M", "UFC", "Lightweight")) }
        send(IngestPayload.EventUpsert("e1", "UFC", "UFC 999", starts))
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2")))
    }

    private fun odds(
        takenAt: String,
        a: Double,
        b: Double,
    ) = send(IngestPayload.OddsSnapshot("b1", Instant.parse(takenAt), a, b))

    private fun drift(
        oddsA: Double,
        oddsB: Double,
    ) = 1 + 0.05 * ((1 / oddsA) / (1 / oddsA + 1 / oddsB) - 0.5)

    @Test
    fun `fight-week odds move the multiplier and a newer snapshot only the difference`() {
        setup()
        odds("2026-12-01T12:00:00Z", 1.5, 3.0)
        assertEquals(1.02 * drift(1.5, 3.0), multiplier("f1"), 1e-5)
        assertEquals(1.02 * drift(3.0, 1.5), multiplier("f2"), 1e-5)
        odds("2026-12-03T12:00:00Z", 1.25, 4.0)
        assertEquals(1.02 * drift(1.25, 4.0), multiplier("f1"), 1e-5)
        assertEquals("ANNOUNCED,ODDS_DRIFT,ODDS_DRIFT", events("f1"))
    }

    @Test
    fun `odds outside the fight week do not move the price`() {
        setup()
        odds("2026-11-01T12:00:00Z", 1.5, 3.0)
        assertEquals(1.02, multiplier("f1"), 1e-9)
        assertEquals("ANNOUNCED", events("f1"))
    }

    @Test
    fun `cancellation takes the odds drift back`() {
        setup()
        odds("2026-12-01T12:00:00Z", 1.5, 3.0)
        send(IngestPayload.BoutCancelled("b1"))
        assertEquals(1.0, multiplier("f1"), 1e-5)
        assertEquals("0", scalar("SELECT count(*) FROM bout_price_drift"))
    }

    @Test
    fun `result resets the multiplier and prices the fighter from the new Elo`() {
        setup()
        odds("2026-12-01T12:00:00Z", 1.5, 3.0)
        send(IngestPayload.BoutResult("b1", "WIN", ref("f1"), "KO_TKO", round = 1))
        assertEquals(1.0, multiplier("f1"), 1e-9)
        assertEquals(1.0, multiplier("f2"), 1e-9)
        assertEquals(Pricing.basePrice(1525.0).units, price("f1"))
        assertEquals(Pricing.basePrice(1475.0).units, price("f2"))
        assertEquals("ANNOUNCED,ODDS_DRIFT,RESULT_RESET", events("f1"))
        assertEquals("0", scalar("SELECT count(*) FROM bout_price_bump"))
        assertEquals("2", scalar("SELECT count(last_fight_at) FROM market_listing"))
    }

    @Test
    fun `a corrected result does not log another reset`() {
        setup()
        send(IngestPayload.BoutResult("b1", "WIN", ref("f1"), "KO_TKO", round = 1))
        send(IngestPayload.BoutResult("b1", "WIN", ref("f1"), "KO_TKO", round = 2))
        assertEquals("ANNOUNCED,RESULT_RESET", events("f1"))
    }

    @Test
    fun `result keeps the bump of the fighter's other upcoming bout`() {
        setup()
        send(IngestPayload.FighterUpsert("f3", "Fighter f3", "M", "UFC", "Lightweight"))
        send(IngestPayload.EventUpsert("e2", "UFC", "UFC 1000", starts.plusSeconds(60L * 86400)))
        send(IngestPayload.BoutAnnounced("e2", "b2", ref("f1"), ref("f3"), "MAIN"))
        send(IngestPayload.BoutResult("b1", "WIN", ref("f1"), "DEC_U"))
        assertEquals(1.05, multiplier("f1"), 1e-5)
    }

    private fun rank(
        at: String,
        vararg ranks: Pair<String, Int>,
    ) = send(
        IngestPayload.RankingSnapshot(
            "UFC",
            "Lightweight",
            Instant.parse(at),
            ranks.map { IngestPayload.RankedFighter(ref(it.first), it.second) },
        ),
    )

    @Test
    fun `ranking changes move prices once per snapshot`() {
        setup()
        rank("2026-11-01T00:00:00Z", "f1" to 5, "f2" to 6)
        assertEquals(1.02, multiplier("f1"), 1e-9)
        rank("2026-11-08T00:00:00Z", "f1" to 3, "f2" to 7)
        assertEquals(1.02 * 1.01, multiplier("f1"), 1e-5)
        assertEquals(1.02 * 0.995, multiplier("f2"), 1e-5)
        rank("2026-11-08T00:00:00Z", "f1" to 3, "f2" to 7)
        assertEquals(1.02 * 1.01, multiplier("f1"), 1e-5)
        assertEquals("ANNOUNCED,RANKING", events("f1"))
    }
}
