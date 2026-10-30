package com.fightexchange.ingest

import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.io.Reader
import java.sql.Connection
import java.sql.Date
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDate
import java.time.ZoneOffset

/** Source of everything the importer writes; re-importing the same file skips bouts that already exist. */
const val HISTORY_SOURCE = "HISTORY"

private const val MAX_REPORTED_ERRORS = 20
private const val FIVE_ROUNDS = 5

@Serdeable
data class HistoryImportResult(
    val organization: String,
    val rows: Int,
    val importedBouts: Int,
    val skippedExisting: Int,
    val skippedInvalid: Int,
    val newFighters: Int,
    val newEvents: Int,
    val errors: List<String>,
)

/**
 * Imports a CSV of past bouts (the `ufc-master.csv` column names of the simulation dataset) and rates them
 * chronologically. Required columns: `date`, `R_fighter`, `B_fighter`, `Winner`, `finish`. Optional: `R_odds`,
 * `B_odds` (American), `finish_round`, `title_bout`, `no_of_rounds`, `gender`, `weight_class`.
 * Rows are ordered by date; rows of the same date keep their order in the file.
 */
@Singleton
class HistoryImporter(
    private val tx: TransactionOperations<Connection>,
    private val support: IngestSupport,
    private val elo: EloService,
) {
    fun import(
        input: Reader,
        organization: String,
    ): HistoryImportResult {
        val table = Csv.parse(input)
        val header = table.firstOrNull()?.map { it.trim() } ?: throw IllegalArgumentException("The CSV is empty")
        val missing = REQUIRED.filter { it !in header }
        require(missing.isEmpty()) { "Missing CSV columns: ${missing.joinToString()}" }
        val columns = header.withIndex().associate { it.value to it.index }
        val errors = mutableListOf<String>()
        val bouts = parseBouts(table.drop(1), columns, errors)
        return tx.executeWrite { status -> write(status.connection, organization, bouts, table.size - 1, errors) }
    }

    private fun write(
        conn: Connection,
        organization: String,
        bouts: List<HistoryBout>,
        rows: Int,
        errors: MutableList<String>,
    ): HistoryImportResult {
        val orgId = support.organizationId(conn, organization)
        val writer = HistoryWriter(conn, orgId, organization.uppercase())
        val newBouts = mutableSetOf<Long>()
        var skipped = 0
        for (bout in bouts) {
            val id = writer.writeBout(bout)
            if (id == null) skipped++ else newBouts += id
        }
        elo.recalculateBouts(conn, newBouts)
        return HistoryImportResult(
            organization,
            rows,
            newBouts.size,
            skipped,
            rows - bouts.size,
            writer.newFighters,
            writer.newEvents,
            errors,
        )
    }

    private fun parseBouts(
        lines: List<List<String>>,
        columns: Map<String, Int>,
        errors: MutableList<String>,
    ): List<HistoryBout> {
        val parsed = mutableListOf<HistoryBout>()
        val perDate = mutableMapOf<LocalDate, Int>()
        for ((index, line) in lines.withIndex()) {
            val row = HistoryRow(line, columns)
            val bout =
                runCatching { row.toBout(perDate) }.getOrElse {
                    if (errors.size < MAX_REPORTED_ERRORS) errors += "Row ${index + 2}: ${it.message}"
                    null
                }
            if (bout != null) parsed += bout
        }
        // Stable sort: same-date rows stay in file order, so bout ids follow the file.
        return parsed.sortedBy { it.date }
    }

    private companion object {
        val REQUIRED = listOf("date", "R_fighter", "B_fighter", "Winner", "finish")
    }
}

private data class HistoryBout(
    val date: LocalDate,
    val ordinal: Int,
    val red: String,
    val blue: String,
    val outcome: String,
    val winnerIsRed: Boolean,
    val method: String,
    val round: Int?,
    val boutType: String,
    val oddsRed: Double?,
    val oddsBlue: Double?,
    val female: Boolean,
    val division: String?,
)

private class HistoryRow(
    private val cells: List<String>,
    private val columns: Map<String, Int>,
) {
    private fun cell(
        name: String,
    ): String? = columns[name]?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

    fun toBout(perDate: MutableMap<LocalDate, Int>): HistoryBout {
        val date = LocalDate.parse(requireNotNull(cell("date")) { "date is empty" })
        val red = requireNotNull(cell("R_fighter")) { "R_fighter is empty" }
        val blue = requireNotNull(cell("B_fighter")) { "B_fighter is empty" }
        require(
            NameNormalizer.normalize(red) != NameNormalizer.normalize(blue),
        ) { "A bout needs two different fighters" }
        val winner = cell("Winner")
        val finish = cell("finish")?.uppercase()
        val result = resultOf(winner, finish)
        val ordinal = perDate.merge(date, 1, Int::plus)!!
        return HistoryBout(
            date,
            ordinal,
            red,
            blue,
            result.outcome,
            winner.equals("Red", ignoreCase = true),
            result.method,
            cell("finish_round")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
            when {
                cell("title_bout").equals("True", ignoreCase = true) -> "TITLE"
                cell("no_of_rounds") == FIVE_ROUNDS.toString() -> "MAIN"
                else -> "NORMAL"
            },
            american(cell("R_odds")),
            american(cell("B_odds")),
            cell("gender")?.startsWith("F", ignoreCase = true) == true,
            cell("weight_class"),
        )
    }

    private class Result(
        val outcome: String,
        val method: String,
    )

    private fun resultOf(
        winner: String?,
        finish: String?,
    ): Result {
        val hasWinner = winner.equals("Red", ignoreCase = true) || winner.equals("Blue", ignoreCase = true)
        return when {
            finish == "CNC" || finish == "OVERTURNED" -> Result("NO_CONTEST", "NC")
            !hasWinner && finish?.contains("DRAW") == true -> Result("DRAW", "DRAW")
            !hasWinner -> Result("NO_CONTEST", "NC")
            else -> Result("WIN", METHODS[finish] ?: "DEC_U")
        }
    }

    /** American odds to decimal odds; `null` when missing or not a valid price. */
    private fun american(text: String?): Double? {
        val value = text?.toDoubleOrNull() ?: return null
        val decimal =
            when {
                value > 0 -> 1 + value / 100
                value < 0 -> 1 + 100 / -value
                else -> return null
            }
        return decimal.takeIf { it > 1 }
    }

    private companion object {
        val METHODS =
            mapOf(
                "KO/TKO" to "KO_TKO",
                "SUB" to "SUB",
                "U-DEC" to "DEC_U",
                "S-DEC" to "DEC_S",
                "M-DEC" to "DEC_M",
                "DQ" to "DQ",
            )
    }
}

/** Inserts the bouts of one import on a single connection, creating fighters and events on first sight. */
private class HistoryWriter(
    private val conn: Connection,
    private val orgId: Long,
    private val orgCode: String,
) {
    var newFighters = 0
    var newEvents = 0

    private val fighters = mutableMapOf<String, Long>()
    private val events = mutableMapOf<LocalDate, Long>()
    private val insertBout = conn.prepareStatement(BOUT_SQL, Statement.RETURN_GENERATED_KEYS)
    private val insertResult = conn.prepareStatement(RESULT_SQL)
    private val insertOdds = conn.prepareStatement(ODDS_SQL)

    /** The new bout id, or `null` when this bout was imported before. */
    fun writeBout(bout: HistoryBout): Long? {
        val externalId = "HIST-$orgCode-${bout.date}-${bout.ordinal}"
        if (boutExists(externalId)) return null
        val eventId = event(bout.date)
        val red = fighter(bout.red, bout.female, bout.division)
        val blue = fighter(bout.blue, bout.female, bout.division)
        val boutId = insert(eventId, red, blue, bout, externalId)
        insertResult(boutId, bout, if (bout.winnerIsRed) red else blue)
        val oddsRed = bout.oddsRed
        val oddsBlue = bout.oddsBlue
        if (oddsRed != null && oddsBlue != null) insertOdds(boutId, bout.date, oddsRed, oddsBlue)
        return boutId
    }

    private fun boutExists(externalId: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM bout WHERE source = ? AND external_id = ?").use { ps ->
            ps.setString(1, HISTORY_SOURCE)
            ps.setString(2, externalId)
            ps.executeQuery().use { it.next() }
        }

    private fun event(date: LocalDate): Long =
        events.getOrPut(date) {
            val externalId = "HIST-$orgCode-$date"
            findEvent(externalId) ?: createEvent(date, externalId)
        }

    private fun findEvent(externalId: String): Long? =
        conn.prepareStatement("SELECT id FROM event WHERE source = ? AND external_id = ?").use { ps ->
            ps.setString(1, HISTORY_SOURCE)
            ps.setString(2, externalId)
            ps.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }

    private fun createEvent(
        date: LocalDate,
        externalId: String,
    ): Long {
        newEvents++
        return conn
            .prepareStatement(
                "INSERT INTO event (organization_id, name, starts_at, local_date, status, source, external_id) " +
                    "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?) RETURNING id",
            ).use { ps ->
                ps.setLong(1, orgId)
                ps.setString(2, "$orgCode $date")
                ps.setTimestamp(3, Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC)))
                ps.setDate(4, Date.valueOf(date))
                ps.setString(5, HISTORY_SOURCE)
                ps.setString(6, externalId)
                ps.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
    }

    private fun fighter(
        name: String,
        female: Boolean,
        division: String?,
    ): Long {
        val normalized = NameNormalizer.normalize(name)
        return fighters.getOrPut(normalized) {
            val externalId = "$orgCode:$normalized"
            findFighter(externalId) ?: createFighter(name, normalized, externalId, female, division)
        }
    }

    private fun findFighter(externalId: String): Long? =
        conn
            .prepareStatement("SELECT fighter_id FROM fighter_external_id WHERE source = ? AND external_id = ?")
            .use { ps ->
                ps.setString(1, HISTORY_SOURCE)
                ps.setString(2, externalId)
                ps.executeQuery().use { if (it.next()) it.getLong(1) else null }
            }

    private fun createFighter(
        name: String,
        normalized: String,
        externalId: String,
        female: Boolean,
        division: String?,
    ): Long {
        newFighters++
        val id =
            conn
                .prepareStatement(
                    "INSERT INTO fighter (name, normalized_name, sex, division, organization_id) " +
                        "VALUES (?, ?, ?, ?, ?) RETURNING id",
                ).use { ps ->
                    ps.setString(1, name)
                    ps.setString(2, normalized)
                    ps.setString(3, if (female) "F" else "M")
                    ps.setString(4, division)
                    ps.setLong(5, orgId)
                    ps.executeQuery().use {
                        it.next()
                        it.getLong(1)
                    }
                }
        conn
            .prepareStatement("INSERT INTO fighter_external_id (fighter_id, source, external_id) VALUES (?, ?, ?)")
            .use { ps ->
                ps.setLong(1, id)
                ps.setString(2, HISTORY_SOURCE)
                ps.setString(3, externalId)
                ps.executeUpdate()
            }
        return id
    }

    private fun insert(
        eventId: Long,
        red: Long,
        blue: Long,
        bout: HistoryBout,
        externalId: String,
    ): Long {
        insertBout.setLong(1, eventId)
        insertBout.setLong(2, red)
        insertBout.setLong(3, blue)
        insertBout.setString(4, bout.boutType)
        insertBout.setString(5, HISTORY_SOURCE)
        insertBout.setString(6, externalId)
        insertBout.executeUpdate()
        return insertBout.generatedKeys.use {
            it.next()
            it.getLong("id")
        }
    }

    private fun insertResult(
        boutId: Long,
        bout: HistoryBout,
        winner: Long,
    ) {
        insertResult.setLong(1, boutId)
        insertResult.setString(2, bout.outcome)
        if (bout.outcome == "WIN") insertResult.setLong(3, winner) else insertResult.setNull(3, Types.BIGINT)
        insertResult.setString(4, bout.method)
        if (bout.round != null) insertResult.setInt(5, bout.round) else insertResult.setNull(5, Types.INTEGER)
        insertResult.executeUpdate()
    }

    private fun insertOdds(
        boutId: Long,
        date: LocalDate,
        oddsRed: Double,
        oddsBlue: Double,
    ) {
        insertOdds.setLong(1, boutId)
        insertOdds.setString(2, HISTORY_SOURCE)
        insertOdds.setTimestamp(3, Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC)))
        insertOdds.setDouble(4, oddsRed)
        insertOdds.setDouble(5, oddsBlue)
        insertOdds.executeUpdate()
    }

    private companion object {
        const val BOUT_SQL =
            "INSERT INTO bout (event_id, fighter_a_id, fighter_b_id, bout_type, status, source, external_id) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?)"
        const val RESULT_SQL =
            "INSERT INTO bout_result (bout_id, version, outcome, winner_id, method, round) VALUES (?, 1, ?, ?, ?, ?)"
        const val ODDS_SQL =
            "INSERT INTO odds_snapshot (bout_id, source, taken_at, odds_a, odds_b, is_closing) " +
                "VALUES (?, ?, ?, ?, ?, TRUE)"
    }
}

/** Minimal RFC 4180 reader: quoted cells may contain commas, quotes (`""`) and line breaks. */
internal object Csv {
    fun parse(
        input: Reader,
    ): List<List<String>> = CsvReader().also { r -> input.buffered().use { it.forEachChar(r::accept) } }.finish()

    private inline fun Reader.forEachChar(action: (Char) -> Unit) {
        while (true) {
            val code = read()
            if (code < 0) break
            action(code.toChar())
        }
    }

    private class CsvReader {
        private val rows = mutableListOf<List<String>>()
        private var row = mutableListOf<String>()
        private val cell = StringBuilder()
        private var quoted = false
        private var afterQuote = false

        fun accept(c: Char) {
            when {
                quoted && c == '"' -> quoted = false.also { afterQuote = true }
                quoted -> cell.append(c)
                afterQuote && c == '"' -> {
                    cell.append('"')
                    quoted = true
                    afterQuote = false
                }
                else -> unquoted(c)
            }
        }

        private fun unquoted(c: Char) {
            afterQuote = false
            when (c) {
                '"' -> quoted = true
                ',' -> endCell()
                '\n' -> endRow()
                '\r' -> Unit
                else -> cell.append(c)
            }
        }

        private fun endCell() {
            row += cell.toString()
            cell.setLength(0)
        }

        private fun endRow() {
            endCell()
            if (row.size > 1 || row[0].isNotEmpty()) rows += row
            row = mutableListOf()
        }

        fun finish(): List<List<String>> {
            if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
            return rows
        }
    }
}
