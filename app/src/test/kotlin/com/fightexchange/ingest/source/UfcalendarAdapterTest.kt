package com.fightexchange.ingest.source

import com.fightexchange.PostgresTest
import com.sun.net.httpserver.HttpServer
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.sql.Connection

/** Recorded-style UFCalendar answers served from a local JDK server; tests swap the card between syncs. */
private object FakeUfcalendar {
    @Volatile var card: String = ""

    @Volatile var status: Int = 200

    val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path
                val body =
                    when {
                        path == "/v1/events" -> events(ex.requestURI.query)
                        path.startsWith("/v1/events/") -> card
                        path == "/v1/rankings/ufc" -> RANKINGS
                        else -> "{}"
                    }
                val bytes = body.toByteArray()
                ex.responseHeaders.add("X-RateLimit-Remaining", "840")
                ex.sendResponseHeaders(if (status == 200) 200 else status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    private fun events(query: String): String =
        if ("org=ufc" in query) {
            """{"data":[{"id":10,"status":"scheduled"}],"meta":{"pagination":{"has_more":false,"next_cursor":null}}}"""
        } else {
            """{"data":[],"meta":{"pagination":{"has_more":false,"next_cursor":null}}}"""
        }

    fun fight(
        id: Int,
        a: Int,
        b: Int,
        status: String = "announced",
        title: Boolean = false,
        extra: String = "",
    ) = """{"id":$id,"ordering":1,"is_title":$title,"is_main":true,"weight_class":"Lightweight","status":"$status",
        |"fighter_a":{"id":$a,"name":"Fighter $a","nickname":null,"country_code":"US"},
        |"fighter_b":{"id":$b,"name":"Fighter $b","nickname":null,"country_code":"BR"}$extra}
        """.trimMargin()

    fun event(vararg fights: String) =
        """{"data":{"id":10,"title":"UFC 999","status":"scheduled","starts_at":"2027-03-06T22:00:00Z",
        |"card":[${fights.joinToString(",")}]},"meta":{}}
        """.trimMargin()

    const val ODDS = ""","odds":{"a":{"decimal":1.5},"b":{"decimal":2.7},"recorded_at":"2027-02-01T10:00:00Z"}"""

    const val RANKINGS =
        """{"data":{"org":"ufc","board":"official","snapshot_date":"2027-02-01","divisions":[
        {"division":"lightweight","entries":[
          {"rank":0,"fighter":{"id":1,"name":"Fighter 1"}},{"rank":1,"fighter":{"id":2,"name":"Fighter 2"}}]},
        {"division":"pound-for-pound","entries":[{"rank":1,"fighter":{"id":1,"name":"Fighter 1"}}]}]},"meta":{}}"""
}

@MicronautTest(transactional = false)
class UfcalendarAdapterTest : PostgresTest() {
    @Inject lateinit var adapter: UfcalendarAdapter

    @Inject lateinit var tx: TransactionOperations<Connection>

    override fun getProperties(): Map<String, String> =
        super.getProperties() +
            mapOf(
                "ingest.ufcalendar.api-key" to "test-key",
                "ingest.ufcalendar.base-url" to "http://127.0.0.1:${FakeUfcalendar.server.address.port}",
            )

    @BeforeEach
    @AfterEach
    fun clean() {
        FakeUfcalendar.status = 200
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "DELETE FROM bout_price_bump; DELETE FROM bout_price_drift; " +
                        "DELETE FROM price_event; DELETE FROM price_daily; DELETE FROM market_listing; " +
                        "DELETE FROM odds_snapshot; DELETE FROM bout_bonus; DELETE FROM weight_miss; " +
                        "DELETE FROM bout_result; DELETE FROM elo_rating; DELETE FROM bout; DELETE FROM event; " +
                        "DELETE FROM ranking_snapshot; DELETE FROM fighter_external_id; DELETE FROM fighter; " +
                        "DELETE FROM review_item; DELETE FROM ingest_event",
                )
            }
        }
    }

    private fun scalar(sql: String): String? =
        tx.executeRead { st ->
            st.connection.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    @Test
    fun `sync creates the card, confirms by odds and repeated syncs stop submitting`() {
        FakeUfcalendar.card =
            FakeUfcalendar.event(FakeUfcalendar.fight(100, 1, 2, title = true, extra = FakeUfcalendar.ODDS))
        val first = adapter.sync()
        assertEquals(840, first.creditsLeft)
        assertEquals("UFC 999", scalar("SELECT name FROM event"))
        assertEquals("2", scalar("SELECT count(*) FROM fighter"))
        assertEquals("TITLE", scalar("SELECT bout_type FROM bout"))
        assertEquals("CONFIRMED", scalar("SELECT status FROM bout"))
        assertEquals("1.5", scalar("SELECT odds_a FROM odds_snapshot"))
        assertEquals("2", scalar("SELECT count(*) FROM ranking_snapshot"))
        // the second sync records the card content of the bout once (a no-op change); after that nothing is new
        assertEquals(1, adapter.sync().submitted)
        assertEquals(0, adapter.sync().submitted)
    }

    @Test
    fun `changed opponent updates the bout and a cancelled bout is held for confirmation`() {
        FakeUfcalendar.card = FakeUfcalendar.event(FakeUfcalendar.fight(100, 1, 2))
        adapter.sync()
        FakeUfcalendar.card = FakeUfcalendar.event(FakeUfcalendar.fight(100, 1, 3))
        adapter.sync()
        assertEquals("Fighter 3", scalar("SELECT name FROM fighter WHERE id = (SELECT fighter_b_id FROM bout)"))
        FakeUfcalendar.card = FakeUfcalendar.event(FakeUfcalendar.fight(100, 1, 3, status = "cancelled"))
        adapter.sync()
        assertEquals("CANCELLATION", scalar("SELECT reason FROM review_item"))
        assertEquals("ANNOUNCED", scalar("SELECT status FROM bout"))
    }

    @Test
    fun `result with a bonus is held for confirmation`() {
        FakeUfcalendar.card = FakeUfcalendar.event(FakeUfcalendar.fight(100, 1, 2))
        adapter.sync()
        val done =
            FakeUfcalendar.fight(
                100,
                1,
                2,
                status = "completed",
                extra =
                    ""","bonus":"Fight of the Night","result":{"winner_fighter_id":1,"method_normalized":"ko_tko",
                    |"round":2,"time":"3:10"}
                    """.trimMargin(),
            )
        FakeUfcalendar.card = FakeUfcalendar.event(done)
        adapter.sync()
        assertEquals("RESULT_CONFIRM", scalar("SELECT reason FROM review_item"))
    }

    @Test
    fun `failed fetch throws so the status records the error`() {
        FakeUfcalendar.status = 429
        assertThrows(IllegalStateException::class.java) { adapter.sync() }
    }
}
