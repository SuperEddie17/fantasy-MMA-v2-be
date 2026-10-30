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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant

@MicronautTest(transactional = false)
class SportApiTest : PostgresTest() {
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
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "DELETE FROM bout_bonus; DELETE FROM weight_miss; DELETE FROM bout_result; " +
                        "DELETE FROM elo_rating; DELETE FROM bout; DELETE FROM event; " +
                        "DELETE FROM fighter_external_id; DELETE FROM fighter; " +
                        "DELETE FROM review_item; DELETE FROM ingest_event",
                )
            }
        }
    }

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
        send(IngestPayload.EventUpsert("e2", "OKT", "Oktagon 1", Instant.parse("2026-03-10T18:00:00Z")))
        send(IngestPayload.BoutAnnounced("e1", "b1", ref("f1"), ref("f2")))
        send(IngestPayload.BoutResult("b1", "WIN", ref("f1"), "KO_TKO"))
    }

    private fun get(uri: String) = client.toBlocking().retrieve(HttpRequest.GET<Any>(uri), String::class.java)

    @Test
    fun `lists the seeded organizations`() {
        val body = get("/api/v1/organizations")
        listOf("UFC", "PFL", "ONE", "OKT", "KSW").forEach { assertTrue(body.contains("\"code\":\"$it\""), body) }
    }

    @Test
    fun `fighters can be filtered by organization, division and diacritics-free name`() {
        seed()
        assertTrue(get("/api/v1/fighters?org=ufc").contains("Rountree"))
        assertTrue(!get("/api/v1/fighters?org=UFC").contains("Vémola"))
        assertTrue(get("/api/v1/fighters?division=Heavyweight").contains("Vémola"))
        assertTrue(get("/api/v1/fighters?q=prochazka").contains("Procházka"))
    }

    @Test
    fun `fighters are paginated by cursor`() {
        seed()
        val first = get("/api/v1/fighters?limit=2")
        assertEquals(2, Regex("\"name\"").findAll(first).count())
        val cursor = Regex("\"nextCursor\":(\\d+)").find(first)?.groupValues?.get(1)
        assertNotNull(cursor, first)
        val second = get("/api/v1/fighters?limit=2&after=$cursor")
        assertEquals(1, Regex("\"name\"").findAll(second).count())
        assertTrue(second.contains("\"nextCursor\":null") || !second.contains("nextCursor"), second)
    }

    @Test
    fun `fighter detail has elo and recent bouts with outcome from the fighter's view`() {
        seed()
        val id = Regex("\"id\":(\\d+),\"name\":\"Jiří").find(get("/api/v1/fighters"))!!.groupValues[1]
        val detail = get("/api/v1/fighters/$id")
        assertTrue(detail.contains("\"result\":\"WIN\""), detail)
        assertTrue(detail.contains("\"opponentName\":\"Khalil Rountree\""), detail)
        assertTrue(Regex("\"elo\":15\\d\\d").containsMatchIn(detail), detail)

        val loserId = Regex("\"id\":(\\d+),\"name\":\"Khalil").find(get("/api/v1/fighters"))!!.groupValues[1]
        assertTrue(get("/api/v1/fighters/$loserId").contains("\"result\":\"LOSS\""))
    }

    @Test
    fun `unknown fighter and event are 404`() {
        listOf("/api/v1/fighters/999999", "/api/v1/events/999999").forEach {
            val e = assertThrows(HttpClientResponseException::class.java) { get(it) }
            assertEquals(HttpStatus.NOT_FOUND, e.status)
        }
    }

    @Test
    fun `events are filtered by date range and organization`() {
        seed()
        val all = get("/api/v1/events")
        assertTrue(all.contains("UFC 1") && all.contains("Oktagon 1"), all)
        assertTrue(!get("/api/v1/events?org=OKT").contains("UFC 1"))
        val ranged = get("/api/v1/events?from=2026-02-01T00:00:00Z&to=2026-04-01T00:00:00Z")
        assertTrue(ranged.contains("Oktagon 1") && !ranged.contains("UFC 1"), ranged)
    }

    @Test
    fun `event card has bouts with fighters and current result`() {
        seed()
        val id = Regex("\"id\":(\\d+),\"organization\":\"UFC\"").find(get("/api/v1/events"))!!.groupValues[1]
        val card = get("/api/v1/events/$id")
        assertTrue(card.contains("\"method\":\"KO_TKO\""), card)
        assertTrue(card.contains("Procházka") && card.contains("Rountree"), card)
        assertNull(Regex("\"bouts\":\\[\\]").find(card))
    }
}
