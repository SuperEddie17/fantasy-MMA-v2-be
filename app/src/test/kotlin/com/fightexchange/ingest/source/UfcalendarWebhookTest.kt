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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant

/** Stands in for the UFCalendar adapter (which needs an API key) and counts its syncs. */
@Singleton
@Requires(property = "test.webhook-adapter", value = "fake")
class FakeUfcalendarAdapter : SourceAdapter {
    override val source = UFCALENDAR_SOURCE
    var syncs = 0
    var failWith: String? = null

    override fun sync(): SyncResult {
        failWith?.let { error(it) }
        syncs++
        return SyncResult(submitted = 1)
    }
}

class WebhookSignatureTest {
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private val body = """{"id":"evt_1"}"""

    @Test
    fun `accepts a valid signature and rejects tampering, wrong secret and stale timestamps`() {
        val t = now.epochSecond
        val header = WebhookSignature.header("s3cret", t, body)
        assertTrue(WebhookSignature.verify(header, body, "s3cret", now, 300))
        assertFalse(WebhookSignature.verify(header, """{"id":"evt_2"}""", "s3cret", now, 300))
        assertFalse(WebhookSignature.verify(header, body, "other", now, 300))
        assertFalse(WebhookSignature.verify(header, body, "s3cret", now.plusSeconds(301), 300))
        assertTrue(WebhookSignature.verify(header, body, "s3cret", now.plusSeconds(300), 300))
        assertFalse(WebhookSignature.verify(null, body, "s3cret", now, 300))
        assertFalse(WebhookSignature.verify("garbage", body, "s3cret", now, 300))
    }

    @Test
    fun `any of several v1 signatures may match`() {
        val t = now.epochSecond
        val good = WebhookSignature.sign("s3cret", t, body)
        assertTrue(WebhookSignature.verify("t=$t,v1=deadbeef,v1=$good", body, "s3cret", now, 300))
    }
}

@MicronautTest(transactional = false)
class UfcalendarWebhookTest : PostgresTest() {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject lateinit var adapter: FakeUfcalendarAdapter

    @Inject lateinit var tx: TransactionOperations<Connection>

    @Inject lateinit var nightly: SourceSyncJob

    override fun getProperties(): Map<String, String> =
        super.getProperties() + mapOf("test.webhook-adapter" to "fake", "ingest.ufcalendar.webhook-secret" to "s3cret")

    @BeforeEach
    fun clean() {
        adapter.syncs = 0
        adapter.failWith = null
        tx.executeWrite { it.connection.createStatement().use { s -> s.execute("DELETE FROM webhook_delivery") } }
    }

    private fun post(
        body: String,
        signature: String? = WebhookSignature.header("s3cret", Instant.now().epochSecond, body),
    ) = client.toBlocking().exchange(
        HttpRequest
            .POST("/api/v1/ingest/webhooks/ufcalendar", body)
            .contentType("application/json")
            .apply { signature?.let { header("X-UFCalendar-Signature", it) } },
        String::class.java,
    )

    private fun status(block: () -> Unit) = assertThrows(HttpClientResponseException::class.java, block).status

    @Test
    fun `signed delivery triggers a sync and a redelivery is ignored`() {
        val body = """{"id":"evt_1","type":"fight-added"}"""
        val first = post(body)
        assertEquals(HttpStatus.OK, first.status)
        assertTrue(first.body()!!.contains("processed"))
        val again = post(body)
        assertTrue(again.body()!!.contains("duplicate"))
        assertEquals(1, adapter.syncs)
        post("""{"id":"evt_2"}""")
        assertEquals(2, adapter.syncs)
    }

    @Test
    fun `bad or missing signature is 401 and nothing is synced`() {
        val body = """{"id":"evt_1"}"""
        assertEquals(HttpStatus.UNAUTHORIZED, status { post(body, signature = null) })
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            status { post(body, WebhookSignature.header("wrong", Instant.now().epochSecond, body)) },
        )
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            status { post(body, WebhookSignature.header("s3cret", Instant.now().epochSecond - 3600, body)) },
        )
        assertEquals(0, adapter.syncs)
    }

    @Test
    fun `payload without id is 400`() {
        assertEquals(HttpStatus.BAD_REQUEST, status { post("""{"type":"x"}""") })
    }

    @Test
    fun `failed sync is 502 and the retry is processed again`() {
        val body = """{"id":"evt_1"}"""
        adapter.failWith = "upstream down"
        assertEquals(HttpStatus.BAD_GATEWAY, status { post(body) })
        adapter.failWith = null
        post(body)
        assertEquals(1, adapter.syncs)
    }

    @Test
    fun `nightly job syncs every registered source`() {
        val outcomes = nightly.syncAll()
        assertTrue(outcomes[UFCALENDAR_SOURCE] is SyncOutcome.Done)
        assertEquals(1, adapter.syncs)
    }
}
