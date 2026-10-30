package com.fightexchange.market

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fightexchange.PostgresTest
import com.fightexchange.ingest.ADMIN_SOURCE
import com.fightexchange.ingest.HistoryImporter
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
import com.fightexchange.ingest.NameNormalizer
import com.fightexchange.rules.RuleSet
import com.fightexchange.rules.sim.Fight
import com.fightexchange.rules.sim.Market
import com.fightexchange.rules.sim.SimOptions
import com.fightexchange.rules.sim.runSeason
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Plays the 2025 UFC season through the real ingest (history before 2025 imported, listings rebuilt by a market
 * replay, then every week: announcements, inactivity job, fight-week odds, results on the fight day) and compares
 * the resulting prices with the reference loop of `sim.py` (its Kotlin port in the rules tests): within ±1 %.
 */
@MicronautTest(transactional = false)
class Season2025PricesTest : PostgresTest() {
    @Inject lateinit var importer: HistoryImporter

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
                    "DELETE FROM bout_price_drift; DELETE FROM bout_price_bump; DELETE FROM price_event; " +
                        "DELETE FROM price_daily; DELETE FROM market_listing; DELETE FROM odds_snapshot; " +
                        "DELETE FROM bout_bonus; DELETE FROM weight_miss; DELETE FROM bout_result; " +
                        "DELETE FROM elo_rating; DELETE FROM bout; DELETE FROM event; " +
                        "DELETE FROM fighter_external_id; DELETE FROM fighter; DELETE FROM review_item; " +
                        "DELETE FROM ingest_event",
                )
            }
        }
    }

    private val rows: JsonNode =
        ObjectMapper().readTree(File(System.getProperty("golden.dir"), "fights.json")).get("rows")

    private val fights: List<Fight> =
        rows.map {
            Fight(
                date = LocalDate.parse(it[0].asText()),
                r = it[1].asText(),
                b = it[2].asText(),
                ro = it[3].takeUnless(JsonNode::isNull)?.asDouble(),
                bo = it[4].takeUnless(JsonNode::isNull)?.asDouble(),
                win = it[5].takeUnless(JsonNode::isNull)?.asText()?.single(),
                finish = it[6].asBoolean(),
                rnd = it[7].asInt(),
                title = it[8].asBoolean(),
                main = it[9].asBoolean(),
                nc = it[10].asBoolean(),
                bonus = it[11].asBoolean(),
            )
        }

    private fun at(date: LocalDate): Instant = date.atStartOfDay().toInstant(ZoneOffset.UTC)

    private fun send(payload: IngestPayload) {
        service.submit(ADMIN_SOURCE, "k${seq++}", payload)
        processor.processPending()
    }

    private fun americanCsv(): String {
        fun american(node: JsonNode): String {
            if (node.isNull) return ""
            val dec = node.asDouble()
            return if (dec >= 2) "%.0f".format((dec - 1) * 100) else "%.0f".format(-100 / (dec - 1))
        }
        val csv =
            StringBuilder("date,R_fighter,B_fighter,R_odds,B_odds,Winner,finish,finish_round,title_bout,no_of_rounds\n")
        for (r in rows.filter { it[0].asText() < "2025" }) {
            val winner = r[5].asText().takeIf { it != "null" }
            val finish =
                if (r[10].asBoolean()) {
                    "CNC"
                } else if (r[6].asBoolean()) {
                    "KO/TKO"
                } else {
                    "U-DEC"
                }
            val colour =
                if (winner == "R") {
                    "Red"
                } else if (winner == "B") {
                    "Blue"
                } else {
                    ""
                }
            csv
                .append(
                    "${r[0].asText()},\"${r[1].asText()}\",\"${r[2].asText()}\",${american(r[3])},${american(r[4])},",
                ).append(
                    "$colour,$finish,${r[7].asInt()},${r[8].asBoolean().toString().replaceFirstChar(Char::uppercase)},",
                ).append("${if (r[9].asBoolean()) 5 else 3}\n")
        }
        return csv.toString()
    }

    private fun fighterIds(): MutableMap<String, Long> =
        tx.executeRead { st ->
            st.connection.createStatement().use { s ->
                s.executeQuery("SELECT normalized_name, id FROM fighter WHERE merged_into_id IS NULL").use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) }.toMutableMap()
                }
            }
        }

    private fun query(sql: String): List<List<Any?>> =
        tx.executeRead { st ->
            st.connection.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) })
                    }
                }
            }
        }

    @Test
    fun `a season played through the ingest ends at the prices of the simulation`() {
        val history = importer.import(americanCsv().reader(), "UFC")
        assertEquals(rows.count { it[0].asText() < "2025" }, history.importedBouts)

        val season = fights.filter { it.date.year == 2025 }
        val start = LocalDate.of(2024, 12, 31)
        val mutableClock = clock as MutableClock
        val ids = fighterIds()

        fun fighterId(name: String): Long {
            val key = NameNormalizer.normalize(name)
            return ids[key] ?: run {
                send(IngestPayload.FighterUpsert("S25-$name", name, "M", "UFC"))
                fighterIds().also { ids.putAll(it) }.getValue(key)
            }
        }
        season.map { it.date }.distinct().forEach {
            send(IngestPayload.EventUpsert("S25-$it", "UFC", "UFC $it", at(it)))
        }
        val boutId = { f: Fight -> "S25-${season.indexOf(f)}" }

        fun announce(f: Fight) {
            val type =
                if (f.title) {
                    "TITLE"
                } else if (f.main) {
                    "MAIN"
                } else {
                    "NORMAL"
                }
            val a = IngestPayload.FighterRef(id = fighterId(f.r))
            val b = IngestPayload.FighterRef(id = fighterId(f.b))
            send(IngestPayload.BoutAnnounced("S25-${f.date}", boutId(f), a, b, type, mutableClock.now))
        }

        // The simulation never announced the fights of its first six weeks (it only knew them as upcoming), so
        // they are announced a day before the start, then the market is rebuilt from the imported history.
        mutableClock.now = at(start.minusDays(1))
        season.filter { it.date.minusWeeks(6) <= start.minusDays(7) }.forEach(::announce)
        assertTrue(replay.replay(from = at(start), to = at(start))!!.listings > 1000)

        var today = start
        var prev = start.minusDays(7)
        while (!today.isAfter(LocalDate.of(2025, 12, 31))) {
            mutableClock.now = at(today)
            season.filter { prev < it.date.minusWeeks(6) && it.date.minusWeeks(6) <= today }.forEach(::announce)
            jobs.runInactivity(at(today))
            val week = season.filter { !it.date.isBefore(today) && it.date.isBefore(today.plusDays(7)) }
            for (f in week.filter { it.ro != null && it.bo != null }) {
                send(IngestPayload.OddsSnapshot(boutId(f), at(today), f.ro!!, f.bo!!, boutSource = ADMIN_SOURCE))
            }
            for (f in week) {
                mutableClock.now = at(f.date)
                send(result(f, ::fighterId, boutId(f)))
            }
            prev = today
            today = today.plusDays(7)
        }

        assertEquals(emptyList<List<Any?>>(), query("SELECT type, payload FROM ingest_event WHERE status <> 'APPLIED'"))
        assertEquals(
            emptyList<List<Any?>>(),
            query(
                "SELECT id FROM bout WHERE status <> 'COMPLETED' AND event_id IN (SELECT id FROM event WHERE source = 'ADMIN')",
            ),
        )
        val reference = runSeason(fights, emptyList(), RuleSet.V2, SimOptions(), seeds = 1).first
        assertSeasonPrices(reference)
    }

    private fun result(
        f: Fight,
        fighterId: (String) -> Long,
        bout: String,
    ): IngestPayload.BoutResult =
        if (f.nc || f.win == null) {
            IngestPayload.BoutResult(bout, "NO_CONTEST", null, "NC", boutSource = ADMIN_SOURCE)
        } else {
            IngestPayload.BoutResult(
                bout,
                "WIN",
                IngestPayload.FighterRef(id = fighterId(f.name(f.win!!))),
                if (f.finish) "KO_TKO" else "DEC_U",
                f.rnd.takeIf { f.finish && it > 0 },
                titleWon = f.title,
                boutSource = ADMIN_SOURCE,
            )
        }

    private fun assertSeasonPrices(reference: Market) {
        val rawNames = (fights.map { it.r } + fights.map { it.b }).distinct()
        // The simulation keys fighters by raw name, so a name spelled two ways is two fighters there and one here;
        // their opponents' Elo (and so prices) then differ legitimately. Such fighters are left out, together with
        // everybody they met since 2024 (and those who met them, and so on).
        val ambiguous =
            rawNames
                .groupBy(NameNormalizer::normalize)
                .filterValues { it.size > 1 }
                .keys
                .toMutableSet()
        for (f in fights.filter { it.date.year >= 2024 }) {
            val r = NameNormalizer.normalize(f.r)
            val b = NameNormalizer.normalize(f.b)
            if (r in ambiguous || b in ambiguous) ambiguous += listOf(r, b)
        }
        val actual =
            query(
                "SELECT f.normalized_name, l.price, l.status FROM market_listing l JOIN fighter f ON f.id = l.fighter_id",
            ).associate { it[0] as String to (it[1] as Long to it[2] as String) }

        val offenders = mutableListOf<String>()
        var compared = 0
        for (name in rawNames) {
            val key = NameNormalizer.normalize(name)
            if (key in ambiguous) continue
            val (price, status) = actual[key] ?: (null to null)
            val simDelisted = name in reference.delisted
            if (simDelisted != (status == "DELISTED")) offenders += "$name: delisted sim=$simDelisted ours=$status"
            if (simDelisted || price == null) continue
            val expected = reference.price(name).units.toDouble()
            compared++
            if (abs(price - expected) > 0.01 * expected) {
                val log =
                    query(
                        "SELECT p.at, p.kind, p.factor, p.price_after, p.note FROM price_event p JOIN fighter f " +
                            "ON f.id = p.fighter_id WHERE f.normalized_name = '$key' AND p.at >= '2025-01-01' ORDER BY p.id",
                    ).joinToString("\n    ")
                offenders += "$name: sim=${expected.toLong()} ours=$price\n    $log"
            }
        }
        assertTrue(compared > 400, "only $compared prices compared")
        assertTrue(
            offenders.isEmpty(),
            "${offenders.size} of $compared fighters off:\n" + offenders.take(25).joinToString("\n"),
        )
    }
}
