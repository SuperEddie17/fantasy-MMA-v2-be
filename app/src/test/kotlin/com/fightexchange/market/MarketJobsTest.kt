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
import java.time.LocalDate
import kotlin.math.pow

@MicronautTest(transactional = false)
class MarketJobsTest : PostgresTest() {
    @Inject lateinit var service: IngestService

    @Inject lateinit var processor: IngestProcessor

    @Inject lateinit var jobs: MarketJobs

    @Inject lateinit var market: MarketService

    @Inject lateinit var tx: TransactionOperations<Connection>

    private var seq = 0
    private val fought = Instant.parse("2025-01-01T12:00:00Z")

    @BeforeEach
    @AfterEach
    fun clean() {
        exec(
            "DELETE FROM bout_price_drift; DELETE FROM bout_price_bump; DELETE FROM price_event; " +
                "DELETE FROM price_daily; DELETE FROM market_listing; DELETE FROM bout_result; " +
                "DELETE FROM elo_rating; DELETE FROM bout; DELETE FROM event; DELETE FROM fighter_external_id; " +
                "DELETE FROM fighter; DELETE FROM review_item; DELETE FROM ingest_event",
        )
    }

    private fun exec(sql: String) = tx.executeWrite { it.connection.createStatement().use { s -> s.execute(sql) } }

    private fun scalar(sql: String): String? =
        tx.executeRead { st ->
            st.connection.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun send(payload: IngestPayload) {
        service.submit(ADMIN_SOURCE, "k${seq++}", payload)
        processor.processPending()
    }

    /** A listed fighter whose IPO and last fight are dated [fought]. */
    private fun fighter(id: String): Long {
        send(IngestPayload.FighterUpsert(id, "Fighter $id", "M", "UFC", "Lightweight"))
        exec(
            "UPDATE market_listing SET listed_at = '$fought', last_fight_at = '$fought'; UPDATE price_event SET at = '$fought'",
        )
        return scalar("SELECT fighter_id FROM fighter_external_id WHERE external_id = '$id'")!!.toLong()
    }

    private fun price(id: Long) = scalar("SELECT price FROM market_listing WHERE fighter_id = $id")!!.toLong()

    private fun status(id: Long) = scalar("SELECT status FROM market_listing WHERE fighter_id = $id")

    private fun near(
        expected: Double,
        actual: Long,
    ) = assertEquals(expected, actual.toDouble(), 2.0)

    private fun weeksAfter(w: Int): Instant = fought.plusSeconds(w * 7L * 86400 + 3600)

    @Test
    fun `no decay up to 26 weeks, then one percent for every further week`() {
        val id = fighter("a")
        val start = price(id)
        jobs.runInactivity(weeksAfter(26))
        assertEquals(start, price(id))
        jobs.runInactivity(weeksAfter(27))
        near(start * 0.99, price(id))
        assertEquals(
            "INACTIVITY",
            scalar("SELECT kind FROM price_event WHERE fighter_id = $id ORDER BY id DESC LIMIT 1"),
        )
    }

    @Test
    fun `running again in the same week changes nothing and a missed week is caught up`() {
        val id = fighter("b")
        val start = price(id)
        jobs.runInactivity(weeksAfter(27))
        val once = price(id)
        jobs.runInactivity(weeksAfter(27).plusSeconds(86400))
        assertEquals(once, price(id))
        jobs.runInactivity(weeksAfter(30))
        near(start * 0.99.pow(4), price(id))
    }

    @Test
    fun `an announced bout stops the decay and the missed weeks are not charged later`() {
        val id = fighter("c")
        fighter("d")
        send(IngestPayload.EventUpsert("e1", "UFC", "UFC 1", weeksAfter(40)))
        val ref = { x: String -> IngestPayload.FighterRef(externalId = x) }
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("c"), ref("d")))
        val announced = price(id)
        jobs.runInactivity(weeksAfter(35))
        assertEquals(announced, price(id))
        exec("UPDATE bout SET status = 'CANCELLED'")
        jobs.runInactivity(weeksAfter(36))
        near(announced * 0.99, price(id))
    }

    @Test
    fun `fighter without an announced bout is delisted after 78 weeks, not before`() {
        val id = fighter("e")
        jobs.runInactivity(weeksAfter(78))
        assertEquals(false, status(id) == "DELISTED")
        jobs.runInactivity(weeksAfter(79))
        assertEquals("DELISTED", status(id))
        assertEquals("DELIST", scalar("SELECT kind FROM price_event WHERE fighter_id = $id ORDER BY id DESC LIMIT 1"))
    }

    @Test
    fun `daily job builds open close low and high from the day's events and is idempotent`() {
        val id = fighter("f")
        val day = LocalDate.parse("2025-03-10")
        val at = { h: Int -> Instant.parse("2025-03-10T0$h:00:00Z") }
        tx.executeWrite {
            market.apply(it.connection, id, PriceCause(PriceEventKind.ANNOUNCED, at(1)), 1.10)
            market.apply(it.connection, id, PriceCause(PriceEventKind.ODDS_DRIFT, at(2)), 0.80)
            market.apply(it.connection, id, PriceCause(PriceEventKind.RANKING, at(3)), 1.05)
        }
        val ann = "FROM price_event WHERE fighter_id = $id AND kind = "
        val open = scalar("SELECT price_before $ann 'ANNOUNCED'")!!.toLong()
        val high = scalar("SELECT price_after $ann 'ANNOUNCED'")!!.toLong()
        val low = scalar("SELECT price_after $ann 'ODDS_DRIFT'")!!.toLong()
        jobs.closePricesThrough(day)
        jobs.closePricesThrough(day)
        val row =
            "SELECT concat_ws(',', open, close, low, high) FROM price_daily WHERE fighter_id = $id AND day = '$day'"
        assertEquals("$open,${price(id)},$low,$high", scalar(row))
        assertEquals("1", scalar("SELECT count(*) FROM price_daily WHERE fighter_id = $id AND day = '$day'"))
    }

    @Test
    fun `days without events carry the last price and a missed night is caught up`() {
        val id = fighter("g")
        val p = price(id)
        jobs.closePricesThrough(LocalDate.parse("2025-03-10"))
        jobs.closePricesThrough(LocalDate.parse("2025-03-13"))
        assertEquals("4", scalar("SELECT count(*) FROM price_daily WHERE fighter_id = $id AND day >= '2025-03-10'"))
        val row =
            "SELECT concat_ws(',', open, close, low, high) FROM price_daily WHERE fighter_id = $id AND day = '2025-03-13'"
        assertEquals("$p,$p,$p,$p", scalar(row))
    }
}
