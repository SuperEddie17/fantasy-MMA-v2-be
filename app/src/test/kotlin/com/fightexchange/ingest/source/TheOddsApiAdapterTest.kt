package com.fightexchange.ingest.source

import com.fightexchange.PostgresTest
import com.sun.net.httpserver.HttpServer
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.sql.Connection
import java.time.Duration
import java.time.Instant

/** Serves a recorded-style `/v4/sports/mma_mixed_martial_arts/odds` answer; tests swap the body. */
private object FakeOddsApi {
    @Volatile var body = "[]"

    @Volatile var status = 200

    @Volatile var lastQuery = ""

    val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                lastQuery = ex.requestURI.path + "?" + ex.requestURI.query
                val bytes = body.toByteArray()
                ex.responseHeaders.add("x-requests-remaining", "19950")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    fun bookmaker(
        key: String,
        home: String,
        homePrice: Double,
        away: String,
        awayPrice: Double,
        updated: String = "2027-03-01T12:00:00Z",
    ) = """{"key":"$key","title":"$key","last_update":"$updated","markets":[{"key":"h2h","outcomes":[
        |{"name":"$home","price":$homePrice},{"name":"$away","price":$awayPrice}]}]}
        """.trimMargin()

    fun fight(
        home: String,
        away: String,
        commence: String,
        vararg bookmakers: String,
    ) = """{"id":"x","sport_key":"mma_mixed_martial_arts","commence_time":"$commence","home_team":"$home",
        |"away_team":"$away","bookmakers":[${bookmakers.joinToString(",")}]}
        """.trimMargin()
}

class OddsPollScheduleTest {
    private val now = Instant.parse("2027-03-01T12:00:00Z")

    @Test
    fun `polls hourly near an event, every 3 hours in its week, otherwise daily, never without an event`() {
        assertEquals(Duration.ofHours(1), OddsPollSchedule.interval(now, now.plus(Duration.ofHours(20))))
        assertEquals(Duration.ofHours(3), OddsPollSchedule.interval(now, now.plus(Duration.ofDays(3))))
        assertEquals(Duration.ofDays(1), OddsPollSchedule.interval(now, now.plus(Duration.ofDays(30))))
        assertNull(OddsPollSchedule.interval(now, null))
    }

    @Test
    fun `due compares the last success with the interval`() {
        val soon = now.plus(Duration.ofHours(5))
        assertTrue(OddsPollSchedule.due(now, null, soon))
        assertTrue(OddsPollSchedule.due(now, now.minus(Duration.ofMinutes(60)), soon))
        assertTrue(!OddsPollSchedule.due(now, now.minus(Duration.ofMinutes(30)), soon))
        assertTrue(!OddsPollSchedule.due(now, null, null))
        val week = now.plus(Duration.ofDays(4))
        assertTrue(!OddsPollSchedule.due(now, now.minus(Duration.ofHours(2)), week))
        assertTrue(OddsPollSchedule.due(now, now.minus(Duration.ofHours(3)), week))
    }
}

@MicronautTest(transactional = false)
class TheOddsApiAdapterTest : PostgresTest() {
    @Inject lateinit var adapter: TheOddsApiAdapter

    @Inject lateinit var tx: TransactionOperations<Connection>

    override fun getProperties(): Map<String, String> =
        super.getProperties() +
            mapOf(
                "ingest.theoddsapi.api-key" to "secret-key",
                "ingest.theoddsapi.base-url" to "http://127.0.0.1:${FakeOddsApi.server.address.port}",
            )

    @BeforeEach
    @AfterEach
    fun clean() {
        FakeOddsApi.status = 200
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "DELETE FROM bout_price_bump; DELETE FROM bout_price_drift; " +
                        "DELETE FROM price_event; DELETE FROM price_daily; DELETE FROM market_listing; " +
                        "DELETE FROM odds_snapshot; DELETE FROM bout; DELETE FROM event; " +
                        "DELETE FROM fighter_external_id; DELETE FROM fighter; " +
                        "DELETE FROM review_item; DELETE FROM ingest_event",
                )
            }
        }
    }

    private fun exec(sql: String) = tx.executeWrite { it.connection.createStatement().use { s -> s.execute(sql) } }

    private fun scalar(sql: String): String? =
        tx.executeRead { st ->
            st.connection.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /** Fighter A "Alex Pereira" vs B "Jiri Prochazka" on an Oktagon-like card starting 2027-03-01 20:00 UTC. */
    private fun bout() {
        exec(
            """
            INSERT INTO fighter (name, normalized_name, sex, division, organization_id)
              SELECT 'Alex Pereira', 'alex pereira', 'M', 'Light Heavyweight', id FROM organization WHERE code = 'UFC';
            INSERT INTO fighter (name, normalized_name, sex, division, organization_id)
              SELECT 'Jiří Procházka', 'jiri prochazka', 'M', 'Light Heavyweight', id FROM organization WHERE code = 'UFC';
            INSERT INTO event (organization_id, name, starts_at, source, external_id)
              SELECT id, 'UFC 999', '2027-03-01T20:00:00Z', 'UFCALENDAR', '10' FROM organization WHERE code = 'UFC';
            INSERT INTO bout (event_id, fighter_a_id, fighter_b_id, source, external_id)
              SELECT (SELECT id FROM event), (SELECT id FROM fighter WHERE name = 'Alex Pereira'),
                     (SELECT id FROM fighter WHERE name = 'Jiří Procházka'), 'UFCALENDAR', '100';
            """.trimIndent(),
        )
    }

    private val commence = "2027-03-01T23:00:00Z"

    @Test
    fun `median odds of the bookmakers are stored in the bout's orientation and confirm the bout`() {
        bout()
        // the feed lists the fighters in the opposite order and without diacritics
        FakeOddsApi.body =
            "[" +
            FakeOddsApi.fight(
                "Jiri Prochazka",
                "Alex Pereira",
                commence,
                FakeOddsApi.bookmaker("draftkings", "Jiri Prochazka", 2.6, "Alex Pereira", 1.5),
                FakeOddsApi.bookmaker("fanduel", "Jiri Prochazka", 2.8, "Alex Pereira", 1.55),
                FakeOddsApi.bookmaker("pinnacle", "Jiri Prochazka", 9.0, "Alex Pereira", 1.4, "2027-03-01T13:00:00Z"),
            ) + "]"
        val result = adapter.sync()
        assertEquals(1, result.submitted)
        assertEquals(19950, result.creditsLeft)
        assertEquals("1.5", scalar("SELECT odds_a FROM odds_snapshot"))
        assertEquals("2.8", scalar("SELECT odds_b FROM odds_snapshot"))
        assertEquals("THEODDSAPI", scalar("SELECT source FROM odds_snapshot"))
        assertEquals("CONFIRMED", scalar("SELECT status FROM bout"))
        assertTrue(FakeOddsApi.lastQuery.startsWith("/v4/sports/mma_mixed_martial_arts/odds?"))
        assertTrue("markets=h2h" in FakeOddsApi.lastQuery && "oddsFormat=decimal" in FakeOddsApi.lastQuery)
    }

    @Test
    fun `unchanged quotes are not stored twice and a newer quote is`() {
        bout()
        val old = FakeOddsApi.bookmaker("draftkings", "Alex Pereira", 1.5, "Jiri Prochazka", 2.6)
        FakeOddsApi.body = "[" + FakeOddsApi.fight("Alex Pereira", "Jiri Prochazka", commence, old) + "]"
        assertEquals(1, adapter.sync().submitted)
        assertEquals(0, adapter.sync().submitted)
        val newer =
            FakeOddsApi.bookmaker(
                "draftkings",
                "Alex Pereira",
                1.4,
                "Jiri Prochazka",
                3.0,
                "2027-03-01T15:00:00Z",
            )
        FakeOddsApi.body = "[" + FakeOddsApi.fight("Alex Pereira", "Jiri Prochazka", commence, newer) + "]"
        assertEquals(1, adapter.sync().submitted)
        assertEquals("2", scalar("SELECT count(*) FROM odds_snapshot"))
    }

    @Test
    fun `fights we do not know or whose event is far away are ignored`() {
        bout()
        val dk = FakeOddsApi.bookmaker("draftkings", "Alex Pereira", 1.5, "Jiri Prochazka", 2.6)
        val other = FakeOddsApi.bookmaker("draftkings", "Nobody One", 1.5, "Nobody Two", 2.6)
        FakeOddsApi.body =
            "[" +
            FakeOddsApi.fight("Nobody One", "Nobody Two", commence, other) + "," +
            FakeOddsApi.fight("Alex Pereira", "Jiri Prochazka", "2027-04-01T23:00:00Z", dk) + "]"
        assertEquals(0, adapter.sync().submitted)
        assertEquals("0", scalar("SELECT count(*) FROM odds_snapshot"))
        assertEquals("0", scalar("SELECT count(*) FROM review_item"))
    }

    @Test
    fun `failed fetch throws without leaking the key`() {
        FakeOddsApi.status = 401
        FakeOddsApi.body = """{"message":"Invalid key"}"""
        val e = assertThrows(IllegalStateException::class.java) { adapter.sync() }
        assertTrue("401" in e.message!! && "secret-key" !in e.message!!)
    }
}
