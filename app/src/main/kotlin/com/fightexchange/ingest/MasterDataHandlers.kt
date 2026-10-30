package com.fightexchange.ingest

import com.fightexchange.market.MarketService
import com.fightexchange.market.PriceCause
import com.fightexchange.market.PriceEventKind
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp

@Singleton
class FighterUpsertHandler(
    private val support: IngestSupport,
    private val matcher: FighterMatcher,
    private val market: MarketService,
) : IngestHandler {
    override val type = IngestEventType.FIGHTER_UPSERT

    override fun handle(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload,
    ): HandlerOutcome {
        payload as IngestPayload.FighterUpsert
        val orgId = support.organizationId(conn, payload.organization)
        val ref = IngestPayload.FighterRef(payload.externalId, payload.name, payload.division)
        return when (val match = matcher.match(conn, event.source, orgId, ref)) {
            is FighterMatch.Matched -> {
                update(conn, match.fighterId, payload)
                linkExternalId(conn, match.fighterId, event.source, payload.externalId)
                list(conn, match.fighterId, event)
                HandlerOutcome.APPLIED
            }
            is FighterMatch.Uncertain -> {
                support.openReview(conn, event.id, ReviewReason.FIGHTER_MATCH)
                HandlerOutcome.NEEDS_REVIEW
            }
            FighterMatch.NotFound -> {
                val id = insert(conn, orgId, payload)
                linkExternalId(conn, id, event.source, payload.externalId)
                list(conn, id, event)
                HandlerOutcome.APPLIED
            }
        }
    }

    /** IPO for a fighter without a listing (a no-op for already listed ones). */
    private fun list(
        conn: Connection,
        fighterId: Long,
        event: StoredIngestEvent,
    ) {
        market.ensureListed(conn, fighterId, PriceCause(PriceEventKind.ELO, support.now(), ingestEventId = event.id))
    }

    private fun insert(
        conn: Connection,
        orgId: Long,
        p: IngestPayload.FighterUpsert,
    ): Long =
        conn
            .prepareStatement(
                "INSERT INTO fighter (name, normalized_name, nickname, sex, division, organization_id, country) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            ).use { ps ->
                ps.setString(1, p.name)
                ps.setString(2, NameNormalizer.normalize(p.name))
                ps.setString(3, p.nickname)
                ps.setString(4, p.sex)
                ps.setString(5, p.division)
                ps.setLong(6, orgId)
                ps.setString(7, p.country)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }

    /** Fields missing from the payload keep their stored value. */
    private fun update(
        conn: Connection,
        id: Long,
        p: IngestPayload.FighterUpsert,
    ) {
        conn
            .prepareStatement(
                "UPDATE fighter SET name = ?, normalized_name = ?, sex = ?, " +
                    "nickname = COALESCE(?, nickname), division = COALESCE(?, division), " +
                    "country = COALESCE(?, country) WHERE id = ?",
            ).use { ps ->
                ps.setString(1, p.name)
                ps.setString(2, NameNormalizer.normalize(p.name))
                ps.setString(3, p.sex)
                ps.setString(4, p.nickname)
                ps.setString(5, p.division)
                ps.setString(6, p.country)
                ps.setLong(7, id)
                ps.executeUpdate()
            }
    }

    private fun linkExternalId(
        conn: Connection,
        fighterId: Long,
        source: String,
        externalId: String,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO fighter_external_id (fighter_id, source, external_id) VALUES (?, ?, ?) " +
                    "ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.setString(2, source)
                ps.setString(3, externalId)
                ps.executeUpdate()
            }
    }
}

@Singleton
class EventUpsertHandler(
    private val support: IngestSupport,
) : IngestHandler {
    override val type = IngestEventType.EVENT_UPSERT

    override fun handle(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload,
    ): HandlerOutcome {
        payload as IngestPayload.EventUpsert
        val orgId = support.organizationId(conn, payload.organization)
        val updated =
            conn
                .prepareStatement(
                    "UPDATE event SET name = ?, starts_at = ?, weigh_in_at = COALESCE(?, weigh_in_at) " +
                        "WHERE source = ? AND external_id = ?",
                ).use { ps ->
                    ps.setString(1, payload.name)
                    ps.setTimestamp(2, Timestamp.from(payload.startsAt))
                    ps.setTimestamp(3, payload.weighInAt?.let { Timestamp.from(it) })
                    ps.setString(4, event.source)
                    ps.setString(5, payload.externalId)
                    ps.executeUpdate()
                }
        if (updated == 0) insert(conn, orgId, event.source, payload)
        return HandlerOutcome.APPLIED
    }

    private fun insert(
        conn: Connection,
        orgId: Long,
        source: String,
        p: IngestPayload.EventUpsert,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO event (organization_id, name, starts_at, weigh_in_at, source, external_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, orgId)
                ps.setString(2, p.name)
                ps.setTimestamp(3, Timestamp.from(p.startsAt))
                ps.setTimestamp(4, p.weighInAt?.let { Timestamp.from(it) })
                ps.setString(5, source)
                ps.setString(6, p.externalId)
                ps.executeUpdate()
            }
    }
}

/** Stores the ranks of fighters it can match for certain; unmatched ones are skipped (rankings are bulk data). */
@Singleton
class RankingSnapshotHandler(
    private val support: IngestSupport,
    private val market: MarketService,
) : IngestHandler {
    override val type = IngestEventType.RANKING_SNAPSHOT

    override fun handle(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload,
    ): HandlerOutcome {
        payload as IngestPayload.RankingSnapshot
        val orgId = support.organizationId(conn, payload.organization)
        val seenBefore = snapshotExists(conn, payload)
        var skipped = 0
        conn
            .prepareStatement(
                "INSERT INTO ranking_snapshot (division, taken_at, fighter_id, rank) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT DO NOTHING",
            ).use { ps ->
                for (ranked in payload.ranks) {
                    val fighterId = support.resolveFighter(conn, event.source, orgId, ranked.fighter)
                    if (fighterId == null) {
                        skipped++
                        continue
                    }
                    ps.setString(1, payload.division)
                    ps.setTimestamp(2, Timestamp.from(payload.takenAt))
                    ps.setLong(3, fighterId)
                    ps.setInt(4, ranked.rank)
                    ps.executeUpdate()
                }
            }
        if (skipped >
            0
        ) {
            log.info("Ranking event {}: {} fighters could not be matched and were skipped", event.id, skipped)
        }
        if (!seenBefore) {
            market.applyRanking(
                conn,
                payload.division,
                payload.takenAt,
                PriceCause(PriceEventKind.RANKING, payload.takenAt, ingestEventId = event.id),
            )
        }
        return HandlerOutcome.APPLIED
    }

    /** A snapshot sent twice must move prices once. */
    private fun snapshotExists(
        conn: Connection,
        payload: IngestPayload.RankingSnapshot,
    ): Boolean =
        conn.prepareStatement("SELECT 1 FROM ranking_snapshot WHERE division = ? AND taken_at = ? LIMIT 1").use { ps ->
            ps.setString(1, payload.division)
            ps.setTimestamp(2, Timestamp.from(payload.takenAt))
            ps.executeQuery().use { it.next() }
        }

    private companion object {
        val log = LoggerFactory.getLogger(RankingSnapshotHandler::class.java)
    }
}
