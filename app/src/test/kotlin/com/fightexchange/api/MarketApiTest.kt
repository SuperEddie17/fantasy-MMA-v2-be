package com.fightexchange.api

import com.fightexchange.PostgresTest
import com.fightexchange.ingest.ADMIN_SOURCE
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant

@MicronautTest(transactional = false)
class MarketApiTest : PostgresTest() {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject lateinit var service: IngestService

    @Inject lateinit var processor: IngestProcessor

    @Inject lateinit var tx: TransactionOperations<Connection>

    private var seq = 0

    @BeforeEach
    @AfterEach
    fun clean() {
        exec(
            "DELETE FROM bout_price_drift; DELETE FROM bout_price_bump; DELETE FROM price_event; " +
                "DELETE FROM price_daily; DELETE FROM market_listing; DELETE FROM bout_bonus; " +
                "DELETE FROM weight_miss; DELETE FROM bout_result; DELETE FROM elo_rating; DELETE FROM bout; " +
                "DELETE FROM event; DELETE FROM fighter_external_id; DELETE FROM fighter; " +
                "DELETE FROM review_item; DELETE FROM ingest_event",
        )
    }

    private fun exec(sql: String) = tx.executeWrite { it.connection.createStatement().use { s -> s.execute(sql) } }

    private fun send(payload: IngestPayload) {
        service.submit(ADMIN_SOURCE, "k${seq++}", payload)
        processor.processPending()
    }

    private fun ref(id: String) = IngestPayload.FighterRef(externalId = id)

    private fun seed() {
        send(IngestPayload.FighterUpsert("f1", "Jiří Procházka", "M", "UFC", "Light Heavyweight"))
        send(IngestPayload.FighterUpsert("f2", "Khalil Rountree", "M", "UFC", "Light Heavyweight"))
        send(IngestPayload.FighterUpsert("f3", "Karlos Vémola", "M", "OKT", "Heavyweight"))
        send(IngestPayload.EventUpsert("e1", "UFC", "UFC 1", Instant.parse("2026-01-10T18:00:00Z")))
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2")))
        send(IngestPayload.BoutResult("b1", "WIN", ref("f1"), "KO_TKO"))
    }

    private fun get(uri: String) = client.toBlocking().retrieve(HttpRequest.GET<Any>(uri), String::class.java)

    private fun idOf(name: String) =
        Regex(
            "\"id\":(\\d+),\"name\":\"$name",
        ).find(get("/api/v1/fighters"))!!.groupValues[1]

    @Test
    fun `overview lists listed fighters filtered by organization and division`() {
        seed()
        val all = get("/api/v1/market")
        assertEquals(3, Regex("\"price\"").findAll(all).count(), all)
        assertTrue(!get("/api/v1/market?org=ufc").contains("Vémola"))
        assertTrue(get("/api/v1/market?org=OKT").contains("Vémola"))
        assertTrue(get("/api/v1/market?division=Heavyweight").let { it.contains("Vémola") && !it.contains("Rountree") })
    }

    @Test
    fun `overview is sorted by price or by 7-day change and paginated by offset`() {
        seed()
        val byPrice =
            Regex("\"name\":\"([^\"]+)\"")
                .findAll(get("/api/v1/market?sort=price"))
                .map {
                    it.groupValues[1]
                }.toList()
        assertEquals("Jiří Procházka", byPrice.first(), byPrice.toString())
        val byChange = get("/api/v1/market?sort=change7d")
        assertTrue(byChange.indexOf("Procházka") < byChange.indexOf("Rountree"), byChange)

        val first = get("/api/v1/market?limit=2")
        assertEquals(2, Regex("\"price\"").findAll(first).count())
        assertTrue(first.contains("\"nextOffset\":2"), first)
        val second = get("/api/v1/market?limit=2&offset=2")
        assertEquals(1, Regex("\"price\"").findAll(second).count())
        assertTrue(!second.contains("nextOffset"), second)

        val e = assertFails("/api/v1/market?sort=bogus")
        assertEquals(HttpStatus.BAD_REQUEST, e.status)
    }

    @Test
    fun `delisted fighters leave the overview but keep their detail`() {
        seed()
        exec("UPDATE market_listing SET status = 'DELISTED', delisted_at = now() WHERE fighter_id = ${idOf("Karlos")}")
        assertTrue(!get("/api/v1/market").contains("Vémola"))
        assertTrue(get("/api/v1/market/${idOf("Karlos")}").contains("\"status\":\"DELISTED\""))
    }

    @Test
    fun `detail breaks the price down into base, multiplier and demand`() {
        seed()
        val detail = get("/api/v1/market/${idOf("Jiří")}")
        listOf("baseValue", "eventMultiplier", "demandFactor", "price", "change7d", "lastFightAt").forEach {
            assertTrue(detail.contains("\"$it\""), detail)
        }
        assertTrue(detail.contains("\"eventMultiplier\":1.0"), detail)
        assertTrue(Regex("\"elo\":15\\d\\d").containsMatchIn(detail), detail)
    }

    @Test
    fun `change over 7 days compares with the price a week ago`() {
        seed()
        val id = idOf("Karlos")
        exec("UPDATE price_event SET at = now() - interval '10 days' WHERE fighter_id = $id")
        exec("UPDATE market_listing SET price = price * 2 WHERE fighter_id = $id")
        assertTrue(get("/api/v1/market/$id").contains("\"change7d\":1.0"), get("/api/v1/market/$id"))
    }

    @Test
    fun `history returns daily prices within the requested range`() {
        seed()
        val id = idOf("Jiří")
        exec(
            "INSERT INTO price_daily (fighter_id, day, open, close, low, high) VALUES " +
                "($id, '2026-01-01', 6000000, 6100000, 5900000, 6200000), " +
                "($id, '2026-01-02', 6100000, 6000000, 5950000, 6150000), " +
                "($id, '2026-01-03', 6000000, 6050000, 6000000, 6050000)",
        )
        val all = get("/api/v1/market/$id/history")
        assertEquals(3, Regex("\"day\"").findAll(all).count(), all)
        assertTrue(all.indexOf("2026-01-01") < all.indexOf("2026-01-03"), all)
        val ranged = get("/api/v1/market/$id/history?from=2026-01-02&to=2026-01-02")
        assertEquals(1, Regex("\"day\"").findAll(ranged).count(), ranged)
        assertTrue(ranged.contains("\"close\":6000000"), ranged)
    }

    @Test
    fun `events explain every price move, newest first, with a cursor`() {
        seed()
        val id = idOf("Jiří")
        val all = get("/api/v1/market/$id/events")
        assertTrue(all.contains("\"kind\":\"ELO\"") && all.contains("\"kind\":\"ANNOUNCED\""), all)
        assertTrue(all.indexOf("RESULT_RESET") < all.indexOf("ANNOUNCED") || !all.contains("RESULT_RESET"), all)

        val first = get("/api/v1/market/$id/events?limit=1")
        assertEquals(1, Regex("\"kind\"").findAll(first).count(), first)
        val cursor = Regex("\"nextCursor\":(\\d+)").find(first)?.groupValues?.get(1)
        assertNotNull(cursor, first)
        val rest = get("/api/v1/market/$id/events?after=$cursor")
        assertTrue(!rest.contains("\"id\":$cursor,"), rest)
        assertTrue(Regex("\"kind\"").findAll(rest).count() >= 1, rest)
    }

    @Test
    fun `unknown fighter is 404 on every market endpoint`() {
        listOf("", "/history", "/events").forEach {
            val e = assertFails("/api/v1/market/999999$it")
            assertEquals(HttpStatus.NOT_FOUND, e.status)
        }
    }

    private fun assertFails(uri: String) =
        org.junit.jupiter.api.Assertions
            .assertThrows(HttpClientResponseException::class.java) { get(uri) }
}
