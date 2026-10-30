package com.fightexchange.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fightexchange.PostgresTest
import com.fightexchange.rules.BoutOutcome
import com.fightexchange.rules.Elo
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection

@MicronautTest(transactional = false)
class HistoryImportTest : PostgresTest() {
    @Inject lateinit var importer: HistoryImporter

    @Inject lateinit var tx: TransactionOperations<Connection>

    @BeforeEach
    @AfterEach
    fun clean() {
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "DELETE FROM odds_snapshot; DELETE FROM bout_bonus; DELETE FROM weight_miss; " +
                        "DELETE FROM bout_result; DELETE FROM elo_rating; DELETE FROM bout; DELETE FROM event; " +
                        "DELETE FROM fighter_external_id; DELETE FROM fighter",
                )
            }
        }
    }

    private fun count(table: String): Int =
        tx.executeRead { st ->
            st.connection.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM $table").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    @Test
    fun `csv reader handles quotes, commas and line breaks`() {
        val rows = Csv.parse("a,\"b,\"\"x\"\"\",c\r\n\"line\nbreak\",,\n\n".reader())
        assertEquals(listOf(listOf("a", "b,\"x\"", "c"), listOf("line\nbreak", "", "")), rows)
    }

    @Test
    fun `missing required columns are rejected`() {
        val e =
            assertThrows(IllegalArgumentException::class.java) { importer.import("date,R_fighter\n".reader(), "UFC") }
        assertTrue(e.message!!.contains("Winner"))
    }

    @Test
    fun `small import maps results, odds and skips broken rows`() {
        val csv =
            """
            date,R_fighter,B_fighter,R_odds,B_odds,Winner,finish,finish_round,title_bout,no_of_rounds
            2020-01-02,Jan Blachowicz,Dominick Réyes,-200,170,Red,KO/TKO,3,True,5
            2020-01-02,Ann One,Bob Two,,,Blue,U-DEC,3,False,3
            2020-01-02,Same,Same,,,Red,SUB,1,False,3
            not-a-date,X,Y,,,Red,SUB,1,False,3
            2020-02-01,Jan Blachowicz,Cee Three,150,-180,,CNC,,False,3
            """.trimIndent()
        val result = importer.import(csv.reader(), "UFC")
        assertEquals(5, result.rows)
        assertEquals(3, result.importedBouts)
        assertEquals(2, result.skippedInvalid)
        assertEquals(2, result.newEvents)
        assertEquals(5, result.newFighters) // "Dominick Réyes" appears once; "Jan Blachowicz" twice
        assertEquals(2, result.errors.size)
        assertEquals(3, count("bout_result"))
        assertEquals(2, count("odds_snapshot"))
        assertEquals(4, count("elo_rating")) // the no contest is not rated
        assertEquals(1, count("bout WHERE bout_type = 'TITLE'"))
        assertEquals(1, count("bout_result WHERE outcome = 'NO_CONTEST' AND method = 'NC'"))

        val again = importer.import(csv.reader(), "UFC")
        assertEquals(0, again.importedBouts)
        assertEquals(3, again.skippedExisting)
        assertEquals(3, count("bout"))
        assertEquals(4, count("elo_rating"))
    }

    /**
     * The whole simulation dataset (2010–2025) must give the Elo of `sim.py`. Fighters are keyed by normalized name:
     * the simulation keys by raw name and so treats 8 spelling variants (`Da-Un Jung` / `Da Un Jung`) as two fighters.
     */
    @Test
    fun `elo of the imported ufc history equals the simulation`() {
        val rows = ObjectMapper().readTree(File(System.getProperty("golden.dir"), "fights.json")).get("rows")
        val csv =
            StringBuilder("date,R_fighter,B_fighter,R_odds,B_odds,Winner,finish,finish_round,title_bout,no_of_rounds\n")
        val expected = mutableMapOf<String, Double>()

        fun key(node: com.fasterxml.jackson.databind.JsonNode) = NameNormalizer.normalize(node.asText())
        for (r in rows) {
            val finish = r[6].asBoolean()
            val nc = r[10].asBoolean()
            val winner = r[5].asText().takeIf { it != "null" }
            csv
                .append("${r[0].asText()},\"${r[1].asText()}\",\"${r[2].asText()}\",")
                .append("${american(r[3])},${american(r[4])},")
                .append(
                    "${when (winner) {
                        "R" -> "Red"
                        "B" -> "Blue"
                        else -> ""
                    }},",
                ).append(
                    "${if (nc) {
                        "CNC"
                    } else if (finish) {
                        "KO/TKO"
                    } else {
                        "U-DEC"
                    }},${r[7].asInt()},",
                ).append(
                    "${r[8].asBoolean().toString().replaceFirstChar {
                        it.uppercase()
                    }},${if (r[9].asBoolean()) 5 else 3}\n",
                )
            if (!nc) {
                val a = expected[key(r[1])] ?: 1500.0
                val b = expected[key(r[2])] ?: 1500.0
                val outcome = if (winner == "R") BoutOutcome.A_WIN else BoutOutcome.B_WIN
                val after = Elo.update(a, b, outcome, finish)
                expected[key(r[1])] = after.a
                expected[key(r[2])] = after.b
            }
        }
        val result = importer.import(csv.toString().reader(), "UFC")
        assertEquals(rows.size(), result.importedBouts)
        assertEquals(0, result.skippedInvalid)

        val actual = mutableMapOf<String, Double>()
        tx.executeRead { st ->
            st.connection.createStatement().use { s ->
                s
                    .executeQuery(
                        "SELECT DISTINCT ON (f.id) f.normalized_name, r.rating_after FROM fighter f " +
                            "JOIN elo_rating r ON r.fighter_id = f.id JOIN bout b ON b.id = r.bout_id " +
                            "JOIN event e ON e.id = b.event_id ORDER BY f.id, e.starts_at DESC, b.id DESC",
                    ).use { rs -> while (rs.next()) actual[rs.getString(1)] = rs.getDouble(2) }
            }
        }
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, elo) -> assertEquals(elo, actual.getValue(name), 1e-6, name) }
    }

    private fun american(node: com.fasterxml.jackson.databind.JsonNode): String {
        if (node.isNull) return ""
        val dec = node.asDouble()
        return if (dec >= 2) "%.0f".format((dec - 1) * 100) else "%.0f".format(-100 / (dec - 1))
    }
}
