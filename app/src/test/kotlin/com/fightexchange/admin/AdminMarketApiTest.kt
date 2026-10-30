package com.fightexchange.admin

import com.fightexchange.PostgresTest
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection

@MicronautTest(transactional = false)
class AdminMarketApiTest :
    PostgresTest(),
    TestPropertyProvider {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject lateinit var tx: TransactionOperations<Connection>

    override fun getProperties(): Map<String, String> = super.getProperties() + ("admin.password" to "secret")

    @BeforeEach
    @AfterEach
    fun clean() {
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "DELETE FROM bout_price_bump; DELETE FROM price_event; DELETE FROM price_daily; " +
                        "DELETE FROM market_listing; DELETE FROM bout_result; DELETE FROM elo_rating; " +
                        "DELETE FROM bout; DELETE FROM event; DELETE FROM fighter_external_id; DELETE FROM fighter; " +
                        "DELETE FROM review_item; DELETE FROM ingest_event",
                )
            }
        }
    }

    private fun token(): String =
        client
            .toBlocking()
            .retrieve(
                HttpRequest.POST("/login", mapOf("username" to "admin", "password" to "secret")),
                String::class.java,
            ).let { Regex("\"access_token\":\"([^\"]+)\"").find(it)!!.groupValues[1] }

    private fun post(
        uri: String,
        body: Any? = null,
        token: String = token(),
    ) = client.toBlocking().exchange(HttpRequest.POST(uri, body).bearerAuth(token), String::class.java)

    private fun status(block: () -> Unit) = assertThrows(HttpClientResponseException::class.java, block).status

    private fun newFighter(name: String): Long =
        Regex("\"id\":(\\d+)")
            .find(
                post(
                    "/api/v1/admin/fighters",
                    mapOf("name" to name, "sex" to "M", "organization" to "UFC", "division" to "Lightweight"),
                ).body()!!,
            )!!
            .groupValues[1]
            .toLong()

    @Test
    fun `admin delists a fighter, the price stays and the change is logged`() {
        val id = newFighter("Delist Me")
        val price = client.toBlocking().retrieve("/api/v1/market/$id")
        val response = post("/api/v1/admin/market/$id/delist", mapOf("reason" to "retired"))
        assertEquals(HttpStatus.OK, response.status)
        assertTrue(response.body()!!.contains("DELISTED"))

        val detail = client.toBlocking().retrieve("/api/v1/market/$id")
        assertTrue(detail.contains("\"status\":\"DELISTED\""), detail)
        val events = client.toBlocking().retrieve("/api/v1/market/$id/events")
        assertTrue(events.contains("\"kind\":\"DELIST\"") && events.contains("retired"), events)
        assertTrue(price.contains("\"price\""), price)
        assertEquals(HttpStatus.CONFLICT, status { post("/api/v1/admin/market/$id/delist") })
    }

    @Test
    fun `delisting a fighter that is not listed is a 404 and the endpoints need a token`() {
        assertEquals(HttpStatus.NOT_FOUND, status { post("/api/v1/admin/market/999999/delist") })
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            status {
                client.toBlocking().exchange(
                    HttpRequest.POST("/api/v1/admin/market/1/delist", ""),
                    String::class.java,
                )
            },
        )
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            status {
                client.toBlocking().exchange(
                    HttpRequest.POST("/api/v1/admin/market/replay", mapOf("from" to "2025-01-01")),
                    String::class.java,
                )
            },
        )
    }

    @Test
    fun `replay rebuilds the market and reports what it did`() {
        val id = newFighter("Replay Me")
        val response = post("/api/v1/admin/market/replay", mapOf("from" to "2020-01-01"))
        assertEquals(HttpStatus.OK, response.status)
        val body = response.body()!!
        assertTrue(Regex("\"listings\":1\\b").containsMatchIn(body), body)
        assertTrue(Regex("\"priceEvents\":1\\b").containsMatchIn(body), body)
        assertTrue(client.toBlocking().retrieve("/api/v1/market/$id/events").contains("IPO"))
    }

    @Test
    fun `replay from the future or without a date is a 400`() {
        assertEquals(
            HttpStatus.BAD_REQUEST,
            status { post("/api/v1/admin/market/replay", mapOf("from" to "2999-01-01")) },
        )
        assertEquals(HttpStatus.BAD_REQUEST, status { post("/api/v1/admin/market/replay", mapOf("x" to 1)) })
    }
}
