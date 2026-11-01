package com.fightexchange.ingest.source

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
import com.fightexchange.ingest.NameNormalizer
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/** Source name of The Odds API events in `ingest_event`. */
const val THE_ODDS_API_SOURCE = "THEODDSAPI"

/** An open bout of ours that odds can be matched to; [source]/[externalId] address it in the ingest. */
data class OpenBout(
    val id: Long,
    val source: String,
    val externalId: String,
    val namesA: Set<String>,
    val namesB: Set<String>,
) {
    /** The two names are our two fighters, in either order. */
    fun matches(
        x: String,
        y: String,
    ) = (x in namesA && y in namesB) || (x in namesB && y in namesA)
}

/**
 * Pulls head-to-head odds for all MMA (one sport key, `mma_mixed_martial_arts`, covers every organisation; the
 * bookmakers include DraftKings and others) and submits `odds.snapshot` for bouts we already know. The feed has
 * no organisation and no ids, so a fight is matched by the two fighter names (either order) among open bouts of
 * events starting within [matchWindowHours] of its commence time. The odds of a fight are the median over
 * bookmakers per side. Fights we do not know are ignored: bouts are announced by the card feeds or the admin.
 * One call returns every upcoming fight and costs one credit per region.
 */
@Singleton
@Requires(property = "ingest.theoddsapi.api-key", pattern = ".+")
class TheOddsApiAdapter(
    private val ingest: IngestService,
    private val processor: IngestProcessor,
    private val tx: TransactionOperations<Connection>,
    @Value("\${ingest.theoddsapi.api-key}") private val apiKey: String,
    @Value("\${ingest.theoddsapi.base-url}") private val baseUrl: String,
    @Value("\${ingest.theoddsapi.sport}") private val sport: String,
    @Value("\${ingest.theoddsapi.regions}") private val regions: String,
    @Value("\${ingest.theoddsapi.match-window-hours}") private val matchWindowHours: Long,
) : SourceAdapter {
    override val source = THE_ODDS_API_SOURCE

    private val log = LoggerFactory.getLogger(TheOddsApiAdapter::class.java)
    private val json = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Synchronized
    override fun sync(): SyncResult {
        val query =
            "regions=${enc(regions)}&markets=h2h&oddsFormat=decimal&dateFormat=iso&apiKey=${enc(apiKey)}"
        val (events, creditsLeft) = get("/v4/sports/${enc(sport)}/odds?$query")
        var submitted = 0
        var unmatched = 0
        for (fight in events) {
            if (submitFight(fight)) submitted++ else unmatched++
        }
        log.debug("The Odds API: {} fights, {} snapshots submitted, {} not ours", events.size(), submitted, unmatched)
        processor.processPending()
        return SyncResult(submitted, creditsLeft)
    }

    /** Submits the snapshot of one fight; `false` when the fight is not one of our open bouts. */
    private fun submitFight(fight: JsonNode): Boolean {
        val quote = quote(fight) ?: return false
        val bout = findBout(fight, quote) ?: return false
        val key = "odds:${bout.id}:${quote.takenAt}"
        if (ingest.exists(source, key)) return false
        val aIsHome = quote.home in bout.namesA
        ingest.submit(
            source,
            key,
            IngestPayload.OddsSnapshot(
                boutExternalId = bout.externalId,
                takenAt = quote.takenAt,
                oddsA = if (aIsHome) quote.homeOdds else quote.awayOdds,
                oddsB = if (aIsHome) quote.awayOdds else quote.homeOdds,
                boutSource = bout.source,
            ),
        )
        return true
    }

    private class Quote(
        val home: String,
        val away: String,
        val homeOdds: Double,
        val awayOdds: Double,
        val takenAt: Instant,
        val commence: Instant,
    )

    /** Median odds per side over all bookmakers quoting both fighters; `null` when nobody does. */
    private fun quote(fight: JsonNode): Quote? {
        val home = NameNormalizer.normalize(fight.path("home_team").asText())
        val away = NameNormalizer.normalize(fight.path("away_team").asText())
        val quotes = fight.path("bookmakers").mapNotNull { bookmakerQuote(it, home, away) }
        val commence = runCatching { Instant.parse(fight.path("commence_time").asText()) }.getOrNull()
        if (quotes.isEmpty() || commence == null) return null
        return Quote(
            home,
            away,
            median(quotes.map { it.homeOdds }),
            median(quotes.map { it.awayOdds }),
            quotes.maxOf { it.updated },
            commence,
        )
    }

    private class BookmakerQuote(
        val homeOdds: Double,
        val awayOdds: Double,
        val updated: Instant,
    )

    private fun bookmakerQuote(
        bookmaker: JsonNode,
        home: String,
        away: String,
    ): BookmakerQuote? {
        val market = bookmaker.path("markets").firstOrNull { it.path("key").asText() == "h2h" } ?: return null
        val prices =
            market.path("outcomes").associate {
                NameNormalizer.normalize(it.path("name").asText()) to it.path("price").asDouble()
            }
        val h = prices[home]
        val a = prices[away]
        val updated = runCatching { Instant.parse(bookmaker.path("last_update").asText()) }.getOrNull()
        if (h == null || a == null || updated == null) return null
        return if (h > 1.0 && a > 1.0) BookmakerQuote(h, a, updated) else null
    }

    private fun findBout(
        fight: JsonNode,
        quote: Quote,
    ): OpenBout? {
        val window = Duration.ofHours(matchWindowHours)
        val candidates = openBouts(quote.commence.minus(window), quote.commence.plus(window))
        val hits =
            candidates.filter { it.matches(quote.home, quote.away) }
        if (hits.size > 1) log.warn("The Odds API: fight {} matches several bouts, skipped", fight.path("id").asText())
        return hits.singleOrNull()
    }

    private fun openBouts(
        from: Instant,
        to: Instant,
    ): List<OpenBout> =
        tx.executeRead { status ->
            status.connection
                .prepareStatement(
                    "SELECT b.id, b.source, b.external_id, fa.name, fb.name FROM bout b " +
                        "JOIN event e ON e.id = b.event_id " +
                        "JOIN fighter fa ON fa.id = b.fighter_a_id JOIN fighter fb ON fb.id = b.fighter_b_id " +
                        "WHERE b.status IN ('ANNOUNCED', 'CONFIRMED', 'NEEDS_REVIEW') AND b.external_id IS NOT NULL " +
                        "AND e.starts_at BETWEEN ? AND ?",
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp.from(from))
                    ps.setTimestamp(2, Timestamp.from(to))
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    OpenBout(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        NameNormalizer.variants(rs.getString(4)).toSet(),
                                        NameNormalizer.variants(rs.getString(5)).toSet(),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    private fun get(path: String): Pair<JsonNode, Int?> {
        val request =
            HttpRequest
                .newBuilder(URI.create(baseUrl.trimEnd('/') + path))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            // the URL carries the key, so only the status and the start of the body are reported
            "The Odds API answered ${response.statusCode()}: ${response.body().take(ERROR_BODY_CHARS)}"
        }
        val credits =
            response
                .headers()
                .firstValue("x-requests-remaining")
                .map { it.toDoubleOrNull()?.toInt() }
                .orElse(null)
        return json.readTree(response.body()) to credits
    }

    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val ERROR_BODY_CHARS = 200

        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
