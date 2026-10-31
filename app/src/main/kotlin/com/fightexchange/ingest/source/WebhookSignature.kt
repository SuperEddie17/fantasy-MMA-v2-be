package com.fightexchange.ingest.source

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * UFCalendar webhook signature: header `X-UFCalendar-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256("<t>.<body>")>`.
 * A delivery is accepted when some `v1` matches and `t` is within [toleranceSeconds] of now.
 */
object WebhookSignature {
    fun sign(
        secret: String,
        timestamp: Long,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac
            .doFinal(
                "$timestamp.$body".toByteArray(StandardCharsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }
    }

    fun header(
        secret: String,
        timestamp: Long,
        body: String,
    ) = "t=$timestamp,v1=${sign(secret, timestamp, body)}"

    fun verify(
        header: String?,
        body: String,
        secret: String,
        now: Instant,
        toleranceSeconds: Long,
    ): Boolean {
        if (header.isNullOrBlank()) return false
        val parts = header.split(',').map { it.trim().split('=', limit = 2) }.filter { it.size == 2 }
        val timestamp = parts.firstOrNull { it[0] == "t" }?.get(1)?.toLongOrNull() ?: return false
        if (abs(now.epochSecond - timestamp) > toleranceSeconds) return false
        val expected = sign(secret, timestamp, body).toByteArray(StandardCharsets.UTF_8)
        return parts.filter { it[0] == "v1" }.any {
            MessageDigest.isEqual(expected, it[1].lowercase().toByteArray(StandardCharsets.UTF_8))
        }
    }
}
