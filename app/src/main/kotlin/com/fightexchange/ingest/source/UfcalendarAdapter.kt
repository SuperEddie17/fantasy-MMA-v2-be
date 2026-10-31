package com.fightexchange.ingest.source

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Source name of UFCalendar events in `ingest_event`. */
const val UFCALENDAR_SOURCE = "UFCALENDAR"

/**
 * Pulls UFC, PFL and Oktagon from the UFCalendar API (`/v1/events` window, each card with `include=odds`, UFC
 * rankings) and submits ingest events. Every submit has a content-based key, so an unchanged card submits nothing
 * and a changed one (opponent, time, corrected result) submits only the change. The feed carries no time of
 * announcement, so `announcedAt` stays empty and ingest stamps the time it first saw the bout.
 */
@Singleton
@Requires(property = "ingest.ufcalendar.api-key", pattern = ".+")
class UfcalendarAdapter(
    private val ingest: IngestService,
    private val processor: IngestProcessor,
    private val clock: Clock,
    @Value("\${ingest.ufcalendar.api-key}") private val apiKey: String,
    @Value("\${ingest.ufcalendar.base-url}") private val baseUrl: String,
    @Value("\${ingest.ufcalendar.orgs}") private val orgs: List<String>,
    @Value("\${ingest.ufcalendar.days-ahead}") private val daysAhead: Long,
    @Value("\${ingest.ufcalendar.days-back}") private val daysBack: Long,
) : SourceAdapter {
    override val source = UFCALENDAR_SOURCE

    private val json = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private var creditsLeft: Int? = null
    private var submitted = 0

    @Synchronized
    override fun sync(): SyncResult {
        submitted = 0
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        for (org in orgs) {
            val code = ORG_CODES[org] ?: error("Unsupported UFCalendar org: $org")
            val seen = mutableSetOf<Long>()
            for (summary in eventList(org, today.minusDays(daysBack), today.plusDays(daysAhead))) {
                val event = get("/v1/events/${summary.path("id").asLong()}?include=odds").path("data")
                syncEvent(code, event, seen)
            }
        }
        if ("ufc" in orgs) syncRankings()
        processor.processPending()
        return SyncResult(submitted, creditsLeft)
    }

    private fun eventList(
        org: String,
        from: LocalDate,
        to: LocalDate,
    ): List<JsonNode> {
        val out = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val suffix = cursor?.let { "&cursor=" + URLEncoder.encode(it, StandardCharsets.UTF_8) } ?: ""
            val page = get("/v1/events?org=$org&from=$from&to=$to&order=asc&limit=100$suffix")
            page.path("data").forEach { out.add(it) }
            val next = page.path("meta").path("pagination")
            cursor = if (next.path("has_more").asBoolean()) next.path("next_cursor").asText(null) else null
        } while (cursor != null)
        return out
    }

    private fun syncEvent(
        org: String,
        event: JsonNode,
        seenFighters: MutableSet<Long>,
    ) {
        if (event.path("status").asText() == "cancelled") return
        val eventId = event.path("id").asText()
        val startsAt = Instant.parse(event.path("starts_at").asText())
        val card = event.path("card").filter { it.hasNonNull("fighter_a") && it.hasNonNull("fighter_b") }
        card.forEach { fight ->
            for (corner in listOf("fighter_a", "fighter_b")) {
                val f = fight.path(corner)
                if (seenFighters.add(f.path("id").asLong())) fighterUpsert(org, fight, f)
            }
        }
        val title = event.path("title").asText()
        submit("event:$eventId:$startsAt:$title", IngestPayload.EventUpsert(eventId, org, title, startsAt))
        card.forEach { syncBout(eventId, it) }
    }

    private fun fighterUpsert(
        org: String,
        fight: JsonNode,
        fighter: JsonNode,
    ) {
        val weightClass = fight.path("weight_class").asText(null)
        val sex = if (weightClass?.contains("women", ignoreCase = true) == true) "F" else "M"
        val payload =
            IngestPayload.FighterUpsert(
                externalId = fighter.path("id").asText(),
                name = fighter.path("name").asText(),
                sex = sex,
                organization = org,
                division = weightClass,
                nickname = fighter.path("nickname").asText(null),
                country = fighter.path("country_code").asText(null),
            )
        submit("fighter:${payload.externalId}:${payload.name}:${payload.division}", payload)
    }

    private fun syncBout(
        eventId: String,
        fight: JsonNode,
    ) {
        val boutId = fight.path("id").asText()
        val a = ref(fight.path("fighter_a"), fight)
        val b = ref(fight.path("fighter_b"), fight)
        val type =
            when {
                fight.path("is_title").asBoolean() -> "TITLE"
                fight.path("is_main").asBoolean() -> "MAIN"
                else -> "NORMAL"
            }
        val announcedBefore = ingest.exists(source, "bout:$boutId")
        submit("bout:$boutId", IngestPayload.BoutAnnounced(eventId, boutId, a, b, type))
        if (announcedBefore) {
            submit(
                "bout-card:$boutId:${a.externalId}:${b.externalId}:$type",
                IngestPayload.BoutChanged(boutId, fighterA = a, fighterB = b, boutType = type),
            )
        }
        val status = fight.path("status").asText()
        fight.path("odds").takeIf { it.isObject }?.let { odds(boutId, it, status) }
        when (status) {
            "cancelled" -> submit("cancel:$boutId", IngestPayload.BoutCancelled(boutId, reason = "cancelled by source"))
            "completed", "no_contest" -> result(boutId, fight, a, b, status)
        }
    }

    private fun ref(
        fighter: JsonNode,
        fight: JsonNode,
    ) = IngestPayload.FighterRef(
        externalId = fighter.path("id").asText(),
        name = fighter.path("name").asText(),
        division = fight.path("weight_class").asText(null),
    )

    private fun odds(
        boutId: String,
        odds: JsonNode,
        status: String,
    ) {
        val recordedAt = Instant.parse(odds.path("recorded_at").asText())
        val settled = status == "completed" || status == "no_contest"
        submit(
            "odds:$boutId:$recordedAt",
            IngestPayload.OddsSnapshot(
                boutExternalId = boutId,
                takenAt = recordedAt,
                oddsA = odds.path("a").path("decimal").asDouble(),
                oddsB = odds.path("b").path("decimal").asDouble(),
                closing = settled,
            ),
        )
    }

    private fun result(
        boutId: String,
        fight: JsonNode,
        a: IngestPayload.FighterRef,
        b: IngestPayload.FighterRef,
        status: String,
    ) {
        val r = fight.path("result")
        val method = METHODS[r.path("method_normalized").asText()] ?: return
        val winnerId = r.path("winner_fighter_id").takeIf { it.isNumber }?.asText()
        val winner = listOf(a, b).firstOrNull { it.externalId == winnerId }
        val outcome =
            when {
                status == "no_contest" -> "NO_CONTEST"
                winner == null -> "DRAW"
                else -> "WIN"
            }
        val isTitle = fight.path("is_title").asBoolean()
        val payload =
            IngestPayload.BoutResult(
                boutExternalId = boutId,
                outcome = outcome,
                winner = winner,
                method = if (outcome == "NO_CONTEST") "NC" else method,
                round = r.path("round").takeIf { it.isNumber }?.asInt(),
                time = r.path("time").asText(null),
                titleWon = isTitle && winner != null,
                bonuses = bonuses(fight.path("bonus").asText(null), winner, a, b),
            )
        val key = "result:$boutId:$outcome:${winner?.externalId}:${payload.method}:${payload.round}:${payload.time}"
        submit(key, payload)
    }

    /** Fight of the Night goes to both corners, any other bonus label to the winner only. */
    private fun bonuses(
        label: String?,
        winner: IngestPayload.FighterRef?,
        a: IngestPayload.FighterRef,
        b: IngestPayload.FighterRef,
    ): List<IngestPayload.Bonus> =
        when {
            label == null -> emptyList()
            label.contains("fight", ignoreCase = true) ->
                listOf(a, b).map { IngestPayload.Bonus(it, "FIGHT_OF_NIGHT") }
            winner != null -> listOf(IngestPayload.Bonus(winner, "PERFORMANCE"))
            else -> emptyList()
        }

    private fun syncRankings() {
        val board = get("/v1/rankings/ufc").path("data")
        val takenAt = LocalDate.parse(board.path("snapshot_date").asText()).atStartOfDay().toInstant(ZoneOffset.UTC)
        for (division in board.path("divisions")) {
            val slug = division.path("division").asText()
            if (slug == "pound-for-pound") continue
            val name = divisionName(slug)
            val ranks =
                division.path("entries").filter { it.hasNonNull("fighter") }.map {
                    val f = it.path("fighter")
                    val ref = IngestPayload.FighterRef(f.path("id").asText(), f.path("name").asText(), name)
                    IngestPayload.RankedFighter(ref, it.path("rank").asInt())
                }
            val key = "ranking:UFC:$slug:${board.path("snapshot_date").asText()}"
            submit(key, IngestPayload.RankingSnapshot("UFC", name, takenAt, ranks))
        }
    }

    private fun submit(
        key: String,
        payload: IngestPayload,
    ) {
        if (ingest.exists(source, key)) return
        ingest.submit(source, key, payload)
        submitted++
    }

    private fun get(path: String): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create(baseUrl.trimEnd('/') + path))
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.headers().firstValue("X-RateLimit-Remaining").ifPresent { creditsLeft = it.toIntOrNull() }
        check(response.statusCode() == 200) {
            "UFCalendar $path answered ${response.statusCode()}: ${response.body().take(ERROR_BODY_CHARS)}"
        }
        return json.readTree(response.body())
    }

    private companion object {
        const val ERROR_BODY_CHARS = 200
        val ORG_CODES = mapOf("ufc" to "UFC", "pfl" to "PFL", "oktagon" to "OKT")
        val METHODS =
            mapOf(
                "ko_tko" to "KO_TKO",
                "submission" to "SUB",
                "technical_submission" to "SUB",
                "decision_unanimous" to "DEC_U",
                "decision_split" to "DEC_S",
                "decision_majority" to "DEC_M",
                "decision" to "DEC_U",
                "draw" to "DRAW",
                "no_contest" to "NC",
                "dq" to "DQ",
            )

        /** `womens-strawweight` → `Women's Strawweight`, the spelling of the weight class on the card. */
        fun divisionName(slug: String): String =
            slug
                .split('-')
                .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                .replace("Womens", "Women's")
    }
}
