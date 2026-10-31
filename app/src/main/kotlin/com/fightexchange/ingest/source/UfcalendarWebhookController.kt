package com.fightexchange.ingest.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.fightexchange.common.ApiException
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.TransactionOperations
import java.sql.Connection
import java.time.Clock

@Serdeable
data class WebhookAck(
    val status: String,
)

/**
 * `POST /api/v1/ingest/webhooks/ufcalendar` (Pro plan webhooks). The signature is checked on the raw body, a
 * redelivered `id` is acknowledged and ignored. The payload only tells us that something changed: the delivery
 * triggers a sync of the source, which fetches the authoritative data (and so also catches up missed deliveries).
 * A failed sync answers 502 without remembering the id, so UFCalendar's retry is processed again.
 */
@Controller("/api/v1/ingest/webhooks/ufcalendar")
@Requires(property = "ingest.ufcalendar.webhook-secret", pattern = ".+")
class UfcalendarWebhookController(
    private val sync: SourceSync,
    private val tx: TransactionOperations<Connection>,
    private val clock: Clock,
    @Value("\${ingest.ufcalendar.webhook-secret}") private val secret: String,
    @Value("\${ingest.ufcalendar.webhook-tolerance-seconds:300}") private val toleranceSeconds: Long,
) {
    private val json = ObjectMapper()

    @Post(consumes = ["application/json"])
    fun receive(
        @Header("X-UFCalendar-Signature") signature: String?,
        @Body body: String,
    ): HttpResponse<WebhookAck> {
        val id = deliveryId(signature, body)
        if (seen(id)) return HttpResponse.ok(WebhookAck("duplicate"))
        when (val outcome = sync.sync(UFCALENDAR_SOURCE)) {
            is SyncOutcome.Done -> remember(id)
            is SyncOutcome.Failed ->
                throw ApiException(HttpStatus.BAD_GATEWAY, "SYNC_FAILED", "Sync failed", outcome.message)
            SyncOutcome.UnknownSource ->
                throw ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SOURCE_DISABLED",
                    "UFCalendar adapter is not configured",
                )
        }
        return HttpResponse.ok(WebhookAck("processed"))
    }

    private fun deliveryId(
        signature: String?,
        body: String,
    ): String {
        if (!WebhookSignature.verify(signature, body, secret, clock.instant(), toleranceSeconds)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Invalid webhook signature")
        }
        val id = runCatching { json.readTree(body).path("id").asText("") }.getOrDefault("")
        if (id.isBlank()) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", "Webhook payload has no id")
        return id
    }

    private fun seen(id: String): Boolean =
        tx.executeRead { status ->
            status.connection
                .prepareStatement("SELECT 1 FROM webhook_delivery WHERE source = ? AND delivery_id = ?")
                .use { ps ->
                    ps.setString(1, UFCALENDAR_SOURCE)
                    ps.setString(2, id)
                    ps.executeQuery().use { it.next() }
                }
        }

    private fun remember(id: String) {
        tx.executeWrite { status ->
            status.connection
                .prepareStatement(
                    "INSERT INTO webhook_delivery (source, delivery_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                ).use { ps ->
                    ps.setString(1, UFCALENDAR_SOURCE)
                    ps.setString(2, id)
                    ps.executeUpdate()
                }
        }
    }
}
