package com.fightexchange.ingest

import com.fightexchange.market.MarketService
import com.fightexchange.market.PriceCause
import com.fightexchange.market.PriceEventKind
import com.fightexchange.payout.PayoutService
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

@Singleton
class BoutAnnouncedHandler(
    private val support: IngestSupport,
    private val market: MarketService,
) : IngestHandler {
    override val type = IngestEventType.BOUT_ANNOUNCED

    override fun handle(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload,
    ): HandlerOutcome {
        payload as IngestPayload.BoutAnnounced
        val (eventId, orgId) = support.eventRow(conn, payload.eventSource ?: event.source, payload.eventExternalId)
        if (exists(conn, event.source, payload.boutExternalId)) return HandlerOutcome.APPLIED
        val a = support.resolveFighter(conn, event.source, orgId, payload.fighterA)
        val b = support.resolveFighter(conn, event.source, orgId, payload.fighterB)
        if (a == null || b == null) {
            support.openReview(conn, event.id, ReviewReason.FIGHTER_MATCH)
            return HandlerOutcome.NEEDS_REVIEW
        }
        val boutId = insert(conn, event.source, eventId, payload, a, b)
        market.syncBout(conn, boutId, priceCause(support.now(), event, boutId))
        return HandlerOutcome.APPLIED
    }

    private fun exists(
        conn: Connection,
        source: String,
        externalId: String,
    ): Boolean =
        conn.prepareStatement("SELECT 1 FROM bout WHERE source = ? AND external_id = ?").use { ps ->
            ps.setString(1, source)
            ps.setString(2, externalId)
            ps.executeQuery().use { it.next() }
        }

    /** A source states its own announcement time (and we note when we saw it); an admin sets the admin time. */
    private fun insert(
        conn: Connection,
        source: String,
        eventId: Long,
        p: IngestPayload.BoutAnnounced,
        a: Long,
        b: Long,
    ): Long {
        val admin = source == ADMIN_SOURCE
        return conn
            .prepareStatement(
                "INSERT INTO bout (event_id, fighter_a_id, fighter_b_id, bout_type, source, external_id, " +
                    "announced_at_source, announced_at_seen, announced_at_admin) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            ).use { ps ->
                val announced = p.announcedAt?.let { Timestamp.from(it) }
                val now = Timestamp.from(support.now())
                ps.setLong(1, eventId)
                ps.setLong(2, a)
                ps.setLong(3, b)
                ps.setString(4, p.boutType)
                ps.setString(5, source)
                ps.setString(6, p.boutExternalId)
                ps.setTimestamp(7, if (admin) null else announced)
                ps.setTimestamp(8, if (admin) null else now)
                ps.setTimestamp(9, if (admin) announced ?: now else null)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
    }
}

@Singleton
class BoutChangedHandler(
    private val support: IngestSupport,
    private val market: MarketService,
) : IngestHandler {
    override val type = IngestEventType.BOUT_CHANGED

    override fun handle(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload,
    ): HandlerOutcome {
        payload as IngestPayload.BoutChanged
        val bout = support.boutRow(conn, payload.boutSource ?: event.source, payload.boutExternalId)
        check(bout.status in OPEN_STATUSES) { "Bout ${payload.boutExternalId} is ${bout.status} and cannot change" }
        val a = payload.fighterA?.let { support.resolveFighter(conn, event.source, bout.organizationId, it) }
        val b = payload.fighterB?.let { support.resolveFighter(conn, event.source, bout.organizationId, it) }
        if (unresolved(payload.fighterA, a) || unresolved(payload.fighterB, b)) {
            support.openReview(conn, event.id, ReviewReason.FIGHTER_MATCH)
            return HandlerOutcome.NEEDS_REVIEW
        }
        conn
            .prepareStatement(
                "UPDATE bout SET fighter_a_id = COALESCE(?, fighter_a_id), fighter_b_id = COALESCE(?, fighter_b_id), " +
                    "bout_type = COALESCE(?, bout_type), announced_at_admin = COALESCE(?, announced_at_admin) " +
                    "WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, a)
                ps.setObject(2, b)
                ps.setString(3, payload.boutType)
                ps.setTimestamp(
                    4,
                    payload.announcedAt?.takeIf { event.source == ADMIN_SOURCE }?.let { Timestamp.from(it) },
                )
                ps.setLong(5, bout.id)
                ps.executeUpdate()
            }
        market.syncBout(conn, bout.id, priceCause(support.now(), event, bout.id, PriceEventKind.ANNOUNCED))
        market.driftBout(conn, bout.id, priceCause(support.now(), event, bout.id, PriceEventKind.ODDS_DRIFT))
        return HandlerOutcome.APPLIED
    }

    private fun unresolved(
        ref: IngestPayload.FighterRef?,
        id: Long?,
    ) = ref != null && id == null

    private companion object {
        val OPEN_STATUSES = setOf("ANNOUNCED", "CONFIRMED", "NEEDS_REVIEW")
    }
}

@Singleton
class BoutCancelledHandler(
    private val support: IngestSupport,
    private val policy: ConfirmationPolicy,
    private val market: MarketService,
) : IngestHandler {
    override val type = IngestEventType.BOUT_CANCELLED

    override fun handle(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload,
    ): HandlerOutcome {
        payload as IngestPayload.BoutCancelled
        if (policy.requiresConfirmation(event)) {
            support.openReview(conn, event.id, ReviewReason.CANCELLATION)
            return HandlerOutcome.NEEDS_REVIEW
        }
        return apply(conn, event, payload)
    }

    /** Also used when an admin confirms a held cancellation. */
    fun apply(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload.BoutCancelled,
    ): HandlerOutcome {
        val bout = support.boutRow(conn, payload.boutSource ?: event.source, payload.boutExternalId)
        check(bout.status != "COMPLETED") { "Bout ${payload.boutExternalId} is already completed" }
        val withdrawn = payload.withdrawnFighter?.let { support.resolveInBout(conn, event.source, bout, it) }
        conn
            .prepareStatement(
                "UPDATE bout SET status = 'CANCELLED', cancel_reason = ?, withdrawn_fighter_id = ?, " +
                    "cancelled_at = COALESCE(cancelled_at, ?) WHERE id = ?",
            ).use { ps ->
                ps.setString(1, payload.reason)
                ps.setObject(2, withdrawn)
                ps.setTimestamp(3, Timestamp.from(support.now()))
                ps.setLong(4, bout.id)
                ps.executeUpdate()
            }
        if (bout.status != "CANCELLED") {
            market.cancelBout(
                conn,
                bout.id,
                withdrawn,
                payload.reason,
                priceCause(support.now(), event, bout.id, PriceEventKind.CANCELLED),
            )
        }
        return HandlerOutcome.APPLIED
    }
}

/** Reason for a price change caused by an ingest event; the market service refines the kind per change. */
private fun priceCause(
    at: Instant,
    event: StoredIngestEvent,
    boutId: Long,
    kind: PriceEventKind = PriceEventKind.ANNOUNCED,
) = PriceCause(kind, at, boutId, event.id)

@Singleton
class OddsSnapshotHandler(
    private val support: IngestSupport,
    private val market: MarketService,
) : IngestHandler {
    override val type = IngestEventType.ODDS_SNAPSHOT

    override fun handle(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload,
    ): HandlerOutcome {
        payload as IngestPayload.OddsSnapshot
        val bout = support.boutRow(conn, payload.boutSource ?: event.source, payload.boutExternalId)
        conn
            .prepareStatement(
                "INSERT INTO odds_snapshot (bout_id, source, taken_at, odds_a, odds_b, is_closing) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, bout.id)
                ps.setString(2, event.source)
                ps.setTimestamp(3, Timestamp.from(payload.takenAt))
                ps.setDouble(4, payload.oddsA)
                ps.setDouble(5, payload.oddsB)
                ps.setBoolean(6, payload.closing)
                ps.executeUpdate()
            }
        market.driftBout(conn, bout.id, PriceCause(PriceEventKind.ODDS_DRIFT, payload.takenAt, bout.id, event.id))
        return HandlerOutcome.APPLIED
    }
}

/** Stores the result as a new version of `bout_result` and recalculates Elo from this bout onward. */
@Singleton
class BoutResultHandler(
    private val support: IngestSupport,
    private val policy: ConfirmationPolicy,
    private val elo: EloService,
    private val market: MarketService,
    private val payouts: PayoutService,
) : IngestHandler {
    override val type = IngestEventType.BOUT_RESULT

    override fun handle(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload,
    ): HandlerOutcome {
        payload as IngestPayload.BoutResult
        if (policy.requiresConfirmation(event)) {
            support.openReview(conn, event.id, ReviewReason.RESULT_CONFIRM)
            return HandlerOutcome.NEEDS_REVIEW
        }
        return apply(conn, event, payload)
    }

    /** Also used when an admin confirms a held result. */
    fun apply(
        conn: Connection,
        event: StoredIngestEvent,
        payload: IngestPayload.BoutResult,
    ): HandlerOutcome {
        val bout = support.boutRow(conn, payload.boutSource ?: event.source, payload.boutExternalId)
        check(bout.status != "CANCELLED") { "Bout ${payload.boutExternalId} is cancelled" }
        val winner = payload.winner?.let { support.resolveInBout(conn, event.source, bout, it) }
        val bonusFighters = payload.bonuses.map { support.resolveInBout(conn, event.source, bout, it.fighter) }
        val missFighters = payload.weightMisses.map { support.resolveInBout(conn, event.source, bout, it) }
        val unresolved = (payload.winner != null && winner == null) || (bonusFighters + missFighters).any { it == null }
        if (unresolved) {
            support.openReview(conn, event.id, ReviewReason.FIGHTER_MATCH)
            return HandlerOutcome.NEEDS_REVIEW
        }
        insertResult(conn, bout, payload, winner)
        replaceChildren(
            conn,
            bout.id,
            payload.bonuses.map {
                it.type
            },
            bonusFighters.filterNotNull(),
            missFighters.filterNotNull(),
        )
        conn.prepareStatement("UPDATE bout SET status = 'COMPLETED' WHERE id = ?").use { ps ->
            ps.setLong(1, bout.id)
            ps.executeUpdate()
        }
        // dividends use the price before the result moves it
        payouts.settle(conn, bout.id, support.now())
        elo.recalculateFrom(conn, bout.id)
        market.settleBout(conn, bout.id, priceCause(support.now(), event, bout.id, PriceEventKind.RESULT_RESET))
        return HandlerOutcome.APPLIED
    }

    private fun insertResult(
        conn: Connection,
        bout: BoutRow,
        p: IngestPayload.BoutResult,
        winner: Long?,
    ) {
        conn.prepareStatement("UPDATE bout_result SET is_current = FALSE WHERE bout_id = ? AND is_current").use { ps ->
            ps.setLong(1, bout.id)
            ps.executeUpdate()
        }
        conn
            .prepareStatement(
                "INSERT INTO bout_result (bout_id, version, outcome, winner_id, method, round, time, title_won) " +
                    "VALUES (?, (SELECT COALESCE(MAX(version), 0) + 1 FROM bout_result WHERE bout_id = ?), " +
                    "?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, bout.id)
                ps.setLong(2, bout.id)
                ps.setString(3, p.outcome)
                ps.setObject(4, winner)
                ps.setString(5, p.method)
                ps.setObject(6, p.round)
                ps.setString(7, p.time)
                ps.setBoolean(8, p.titleWon)
                ps.executeUpdate()
            }
    }

    /** Bonuses and weight misses belong to the current result, so a corrected result replaces them. */
    private fun replaceChildren(
        conn: Connection,
        boutId: Long,
        bonusTypes: List<String>,
        bonusFighters: List<Long>,
        misses: List<Long>,
    ) {
        for (table in listOf("bout_bonus", "weight_miss")) {
            conn.prepareStatement("DELETE FROM $table WHERE bout_id = ?").use { ps ->
                ps.setLong(1, boutId)
                ps.executeUpdate()
            }
        }
        bonusFighters.zip(bonusTypes).forEach { (fighter, type) ->
            conn
                .prepareStatement(
                    "INSERT INTO bout_bonus (bout_id, fighter_id, type) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                ).use { ps ->
                    ps.setLong(1, boutId)
                    ps.setLong(2, fighter)
                    ps.setString(3, type)
                    ps.executeUpdate()
                }
        }
        misses.forEach { fighter ->
            conn
                .prepareStatement("INSERT INTO weight_miss (bout_id, fighter_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .use { ps ->
                    ps.setLong(1, boutId)
                    ps.setLong(2, fighter)
                    ps.executeUpdate()
                }
        }
    }
}
