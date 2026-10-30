package com.fightexchange.trading

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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit

@MicronautTest(transactional = false)
class PortfolioApiTest :
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
        exec(
            "DELETE FROM weekly_buy_counter; DELETE FROM ledger_entry; DELETE FROM position_lot; DELETE FROM trade; " +
                "DELETE FROM portfolio; DELETE FROM season; DELETE FROM app_user; " +
                "DELETE FROM market_listing; DELETE FROM fighter",
        )
    }

    private fun exec(sql: String) = tx.executeWrite { it.connection.createStatement().use { s -> s.execute(sql) } }

    private fun call(request: HttpRequest<*>) = client.toBlocking().exchange(request, String::class.java)

    private fun adminToken() =
        Regex("\"access_token\":\"([^\"]+)\"")
            .find(call(HttpRequest.POST("/login", mapOf("username" to "admin", "password" to "secret"))).body()!!)!!
            .groupValues[1]

    private fun registerToken(email: String) =
        Regex("\"accessToken\":\"([^\"]+)\"")
            .find(
                call(
                    HttpRequest.POST(
                        "/api/v1/auth/register",
                        mapOf(
                            "email" to email,
                            "password" to "correct-horse",
                            "displayName" to email.substringBefore('@'),
                        ),
                    ),
                ).body()!!,
            )!!
            .groupValues[1]

    private fun get(
        uri: String,
        token: String,
    ) = call(HttpRequest.GET<Any>(uri).bearerAuth(token)).body()!!

    private fun createSeason(
        startsAt: Instant = Instant.now().minus(1, ChronoUnit.DAYS),
        endsAt: Instant = Instant.now().plus(100, ChronoUnit.DAYS),
    ) = call(
        HttpRequest
            .POST(
                "/api/v1/admin/seasons",
                mapOf("name" to "Season 1", "startsAt" to startsAt.toString(), "endsAt" to endsAt.toString()),
            ).bearerAuth(adminToken()),
    )

    private fun join(
        token: String,
        body: Any? = null,
    ) = call(HttpRequest.POST("/api/v1/portfolios", body ?: emptyMap<String, Any>()).bearerAuth(token))

    private fun statusAndCode(block: () -> Unit): Pair<HttpStatus, String> {
        val e = assertThrows(HttpClientResponseException::class.java, block)
        val body = e.response.getBody(String::class.java).orElse("")
        return e.status to (Regex("\"code\":\"([A-Z_]+)\"").find(body)?.groupValues?.get(1) ?: "")
    }

    private fun idOf(body: String) = Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()

    private fun fighter(
        name: String,
        price: Long,
    ): Long {
        exec(
            "INSERT INTO fighter (name, normalized_name, sex, organization_id) " +
                "SELECT '$name', lower('$name'), 'M', id FROM organization WHERE code = 'UFC'",
        )
        val id =
            tx.executeRead {
                it.connection.createStatement().use { s ->
                    s.executeQuery("SELECT id FROM fighter WHERE name = '$name'").use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }
        exec(
            "INSERT INTO market_listing (fighter_id, status, base_value, price) VALUES ($id, 'ACTIVE', $price, $price)",
        )
        return id
    }

    @Test
    fun `joining needs a running season and books the starting budget`() {
        val token = registerToken("ann@example.com")
        assertEquals(HttpStatus.CONFLICT to "NO_ACTIVE_SEASON", statusAndCode { join(token) })

        val season = createSeason()
        assertEquals(HttpStatus.CREATED, season.status)
        assertTrue(season.body()!!.contains("\"ruleSetVersion\":2"), season.body())

        val portfolio = join(token)
        assertEquals(HttpStatus.CREATED, portfolio.status)
        val body = portfolio.body()!!
        assertTrue(body.contains("\"cash\":100000000"), body)
        assertTrue(body.contains("\"netWorth\":100000000"), body)

        val ledger = get("/api/v1/portfolios/${idOf(body)}/ledger", token)
        assertTrue(ledger.contains("\"kind\":\"START_BUDGET\"") && ledger.contains("\"amount\":100000000"), ledger)
        assertEquals(HttpStatus.CONFLICT to "PORTFOLIO_EXISTS", statusAndCode { join(token) })
        assertTrue(get("/api/v1/portfolios", token).contains("\"cash\":100000000"))
    }

    @Test
    fun `an ended season cannot be joined and a season id picks the season`() {
        val token = registerToken("ann@example.com")
        val old =
            idOf(
                createSeason(
                    Instant.now().minus(30, ChronoUnit.DAYS),
                    Instant.now().minus(1, ChronoUnit.DAYS),
                ).body()!!,
            )
        assertEquals(HttpStatus.CONFLICT to "SEASON_ENDED", statusAndCode { join(token, mapOf("seasonId" to old)) })
        assertEquals(
            HttpStatus.NOT_FOUND to "SEASON_NOT_FOUND",
            statusAndCode {
                join(
                    token,
                    mapOf(
                        "seasonId" to old + 999,
                    ),
                )
            },
        )
        createSeason()
        assertEquals(HttpStatus.CREATED, join(token).status)
    }

    @Test
    fun `seasons are created by the admin only and the end must follow the start`() {
        val token = registerToken("ann@example.com")
        assertEquals(
            HttpStatus.FORBIDDEN,
            assertThrows(HttpClientResponseException::class.java) {
                call(
                    HttpRequest
                        .POST(
                            "/api/v1/admin/seasons",
                            mapOf(
                                "name" to "x",
                                "startsAt" to "2026-01-01T00:00:00Z",
                                "endsAt" to "2027-01-01T00:00:00Z",
                            ),
                        ).bearerAuth(token),
                )
            }.status,
        )
        val bad =
            assertThrows(HttpClientResponseException::class.java) {
                call(
                    HttpRequest
                        .POST(
                            "/api/v1/admin/seasons",
                            mapOf(
                                "name" to "x",
                                "startsAt" to "2027-01-01T00:00:00Z",
                                "endsAt" to "2026-01-01T00:00:00Z",
                            ),
                        ).bearerAuth(adminToken()),
                )
            }
        assertEquals(HttpStatus.BAD_REQUEST, bad.status)
        createSeason()
        assertTrue(call(HttpRequest.GET<Any>("/api/v1/seasons")).body()!!.contains("Season 1"))
    }

    @Test
    fun `a portfolio is private to its owner`() {
        createSeason()
        val ann = registerToken("ann@example.com")
        val bob = registerToken("bob@example.com")
        val id = idOf(join(ann).body()!!)
        assertEquals(
            HttpStatus.NOT_FOUND to "PORTFOLIO_NOT_FOUND",
            statusAndCode { get("/api/v1/portfolios/$id", bob) },
        )
        assertEquals(
            HttpStatus.NOT_FOUND to "PORTFOLIO_NOT_FOUND",
            statusAndCode { get("/api/v1/portfolios/$id/ledger", bob) },
        )
        assertEquals(
            HttpStatus.NOT_FOUND to "PORTFOLIO_NOT_FOUND",
            statusAndCode { get("/api/v1/portfolios/$id/trades", bob) },
        )
        assertEquals(HttpStatus.FORBIDDEN to "PLAYER_ONLY", statusAndCode { get("/api/v1/portfolios", adminToken()) })
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            assertThrows(HttpClientResponseException::class.java) {
                call(HttpRequest.GET<Any>("/api/v1/portfolios/$id"))
            }.status,
        )
    }

    @Test
    fun `net worth values longs and shorts at current prices`() {
        createSeason()
        val token = registerToken("ann@example.com")
        val id = idOf(join(token).body()!!)
        val a = fighter("Long Guy", 6_000_000)
        val b = fighter("Short Guy", 2_000_000)
        exec(
            "INSERT INTO position_lot (portfolio_id, fighter_id, side, opened_at, open_price) VALUES " +
                "($id, $a, 'LONG', now(), 5000000), ($id, $b, 'SHORT', now(), 3000000)",
        )
        val view = get("/api/v1/portfolios/$id", token)
        assertTrue(view.contains("\"longValue\":6000000"), view)
        assertTrue(view.contains("\"shortValue\":2000000"), view)
        assertTrue(view.contains("\"netWorth\":104000000"), view)
        assertEquals(2, Regex("\"lotId\"").findAll(view).count(), view)
    }

    @Test
    fun `ledger and trades page newest first by cursor`() {
        createSeason()
        val token = registerToken("ann@example.com")
        val id = idOf(join(token).body()!!)
        val f = fighter("Pager", 1_000_000)
        repeat(3) {
            exec(
                "INSERT INTO trade (portfolio_id, fighter_id, kind, price, at) VALUES ($id, $f, 'BUY', 1000000, now())",
            )
            exec("INSERT INTO ledger_entry (portfolio_id, at, kind, amount) VALUES ($id, now(), 'TRADE', -1000000)")
        }
        val first = get("/api/v1/portfolios/$id/trades?limit=2", token)
        assertEquals(2, Regex("\"kind\":\"BUY\"").findAll(first).count(), first)
        val cursor = Regex("\"nextCursor\":(\\d+)").find(first)?.groupValues?.get(1)
        assertNotNull(cursor, first)
        val second = get("/api/v1/portfolios/$id/trades?limit=2&after=$cursor", token)
        assertEquals(1, Regex("\"kind\":\"BUY\"").findAll(second).count(), second)
        assertTrue(!second.contains("nextCursor\":1"), second)
        val ledger = get("/api/v1/portfolios/$id/ledger?limit=10", token)
        assertEquals(4, Regex("\"kind\":").findAll(ledger).count(), ledger)
    }
}
