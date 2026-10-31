package com.fightexchange.ingest.source

import com.fightexchange.PostgresTest
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Duration

/** Test adapter whose next sync outcome the test controls. */
@Singleton
@Requires(property = "test.source-adapter", value = "fake")
class FakeAdapter : SourceAdapter {
    override val source = "FAKE"
    var failWith: String? = null
    var credits: Int? = 1000

    override fun sync(): SyncResult {
        failWith?.let { error(it) }
        return SyncResult(submitted = 3, creditsLeft = credits)
    }
}

@MicronautTest(transactional = false)
class SourceOpsApiTest : PostgresTest() {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject lateinit var tx: TransactionOperations<Connection>

    @Inject lateinit var adapter: FakeAdapter

    @Inject lateinit var status: SourceStatusService

    override fun getProperties(): Map<String, String> =
        super.getProperties() + mapOf("admin.password" to "secret", "test.source-adapter" to "fake")

    @BeforeEach
    @AfterEach
    fun clean() {
        adapter.failWith = null
        adapter.credits = 1000
        tx.executeWrite { it.connection.createStatement().use { s -> s.execute("DELETE FROM source_status") } }
    }

    private fun token(): String =
        client
            .toBlocking()
            .retrieve(
                HttpRequest.POST("/login", mapOf("username" to "admin", "password" to "secret")),
                String::class.java,
            ).let { Regex("\"access_token\":\"([^\"]+)\"").find(it)!!.groupValues[1] }

    private fun sources() =
        client.toBlocking().retrieve(
            HttpRequest.GET<Any>("/api/v1/admin/sources").bearerAuth(token()),
            String::class.java,
        )

    private fun sync(source: String) =
        client
            .toBlocking()
            .exchange(
                HttpRequest.POST<Any>("/api/v1/admin/sources/$source/sync", null).bearerAuth(token()),
                String::class.java,
            )

    @Test
    fun `manual sync records success and credits`() {
        val response = sync("FAKE")
        assertEquals(HttpStatus.OK, response.status)
        assertTrue(response.body()!!.contains("\"submitted\":3"))
        val body = sources()
        assertTrue(body.contains("\"source\":\"FAKE\"") && body.contains("\"creditsLeft\":1000"))
        assertFalse(body.contains("\"lastSuccessAt\":null"))
    }

    @Test
    fun `failed sync is a 502 and is recorded`() {
        adapter.failWith = "upstream down"
        val e = assertThrows(HttpClientResponseException::class.java) { sync("FAKE") }
        assertEquals(HttpStatus.BAD_GATEWAY, e.status)
        assertTrue(sources().contains("upstream down"))
    }

    @Test
    fun `unknown source is 404 and the endpoints need the admin token`() {
        assertEquals(
            HttpStatus.NOT_FOUND,
            assertThrows(HttpClientResponseException::class.java) { sync("NOPE") }.status,
        )
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            assertThrows(HttpClientResponseException::class.java) {
                client.toBlocking().exchange(HttpRequest.GET<Any>("/api/v1/admin/sources"), String::class.java)
            }.status,
        )
    }

    @Test
    fun `alerts for a long outage and for running low on credits`() {
        sync("FAKE")
        assertTrue(status.alerts(Duration.ofHours(6), 500).isEmpty())
        adapter.credits = 100
        sync("FAKE")
        assertEquals(listOf("only 100 API credits left"), status.alerts(Duration.ofHours(6), 500).map { it.reason })

        adapter.failWith = "boom"
        sync2()
        // Zero silence tolerance: any error after the last success counts as an outage.
        assertTrue(status.alerts(Duration.ZERO, 0).any { it.reason.contains("boom") })
        assertTrue(status.alerts(Duration.ofHours(6), 0).isEmpty())
    }

    private fun sync2() = runCatching { sync("FAKE") }
}
