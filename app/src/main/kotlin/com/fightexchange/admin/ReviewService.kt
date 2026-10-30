package com.fightexchange.admin

import com.fightexchange.ingest.BoutCancelledHandler
import com.fightexchange.ingest.BoutResultHandler
import com.fightexchange.ingest.HandlerOutcome
import com.fightexchange.ingest.IngestEventType
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
import com.fightexchange.ingest.IngestStatus
import com.fightexchange.ingest.StoredIngestEvent
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection

sealed interface ResolveResult {
    data object Done : ResolveResult

    data object NotFound : ResolveResult

    data class Invalid(
        val message: String,
    ) : ResolveResult

    data class Conflict(
        val message: String,
    ) : ResolveResult
}

/** The admin queue of ingest events that wait for a decision (`review_item`). */
@Singleton
class ReviewService(
    private val tx: TransactionOperations<Connection>,
    private val ingest: IngestService,
    private val processor: IngestProcessor,
    private val results: BoutResultHandler,
    private val cancellations: BoutCancelledHandler,
) {
    fun list(
        status: String,
        after: Long?,
        limit: Int,
    ): List<ReviewItemDto> =
        tx.executeRead { st ->
            st.connection
                .prepareStatement(
                    "SELECT r.id, r.reason, r.status, r.created_at, r.resolved_by, r.resolved_at, " +
                        "e.id, e.source, e.type, e.payload::text FROM review_item r " +
                        "JOIN ingest_event e ON e.id = r.ingest_event_id " +
                        "WHERE r.status = ? AND r.id > ? ORDER BY r.id LIMIT ?",
                ).use { ps ->
                    ps.setString(1, status)
                    ps.setLong(2, after ?: 0)
                    ps.setInt(3, limit)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    ReviewItemDto(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getTimestamp(4).toInstant(),
                                        rs.getString(5),
                                        rs.getTimestamp(6)?.toInstant(),
                                        rs.getLong(7),
                                        rs.getString(8),
                                        rs.getString(9),
                                        rs.getString(10),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    private class Item(
        val reason: String,
        val status: String,
        val event: StoredIngestEvent,
    )

    fun resolve(
        id: Long,
        request: ResolveReviewRequest,
        admin: String,
    ): ResolveResult {
        var rerun = false
        val result =
            try {
                tx.executeWrite { st ->
                    val conn = st.connection
                    val item = lock(conn, id) ?: return@executeWrite ResolveResult.NotFound
                    if (item.status !=
                        "OPEN"
                    ) {
                        return@executeWrite ResolveResult.Conflict("Review item is already ${item.status}")
                    }
                    when (request.action.uppercase()) {
                        "REJECT" -> finish(conn, id, "REJECTED", admin, item.event.id, IngestStatus.REJECTED)
                        "CONFIRM" -> confirm(conn, id, item, admin)
                        "ASSIGN" ->
                            assign(conn, id, item, request, admin).also {
                                rerun = it == ResolveResult.Done && !isHeldType(item.event.type)
                            }
                        else -> ResolveResult.Invalid("action must be CONFIRM, REJECT or ASSIGN")
                    }
                }
            } catch (e: IllegalStateException) {
                // a held change that no longer fits the data (e.g. the bout was completed meanwhile)
                return ResolveResult.Conflict(e.message ?: "Change cannot be applied")
            }
        if (rerun) processor.processPending()
        return result
    }

    private fun confirm(
        conn: Connection,
        id: Long,
        item: Item,
        admin: String,
    ): ResolveResult {
        val outcome =
            apply(conn, item.event)
                ?: return ResolveResult.Invalid("Only held results and cancellations can be confirmed")
        return finish(conn, id, "CONFIRMED", admin, item.event.id, outcome)
    }

    /** Applies a held bout result/cancellation now; `null` when the event is not of a held type. */
    private fun apply(
        conn: Connection,
        event: StoredIngestEvent,
    ): IngestStatus? =
        when (val payload = ingest.parse(event)) {
            is IngestPayload.BoutResult -> results.apply(conn, event, payload).toStatus()
            is IngestPayload.BoutCancelled -> cancellations.apply(conn, event, payload).toStatus()
            else -> null
        }

    private fun assign(
        conn: Connection,
        id: Long,
        item: Item,
        request: ResolveReviewRequest,
        admin: String,
    ): ResolveResult {
        val fighterId = request.fighterId
        val externalId = request.externalId
        val problem =
            when {
                item.reason != "FIGHTER_MATCH" -> "Only FIGHTER_MATCH items can be assigned"
                fighterId == null || externalId.isNullOrBlank() -> "fighterId and externalId are required"
                else -> link(conn, fighterId, item.event.source, externalId)
            }
        if (problem != null) return ResolveResult.Conflict(problem)
        // Held types are applied directly: running them through the processor would hold them again.
        val next = if (isHeldType(item.event.type)) apply(conn, item.event) ?: IngestStatus.NEW else IngestStatus.NEW
        return finish(conn, id, "ASSIGNED", admin, item.event.id, next)
    }

    private fun isHeldType(type: IngestEventType) =
        type == IngestEventType.BOUT_RESULT || type == IngestEventType.BOUT_CANCELLED

    /** Links the source's external id to the chosen fighter; returns a problem text if that is not possible. */
    private fun link(
        conn: Connection,
        fighterId: Long,
        source: String,
        externalId: String,
    ): String? {
        val exists =
            conn.prepareStatement("SELECT 1 FROM fighter WHERE id = ? AND merged_into_id IS NULL").use { ps ->
                ps.setLong(1, fighterId)
                ps.executeQuery().use { it.next() }
            }
        if (!exists) return "Fighter $fighterId does not exist or was merged"
        val current =
            conn
                .prepareStatement("SELECT fighter_id FROM fighter_external_id WHERE source = ? AND external_id = ?")
                .use { ps ->
                    ps.setString(1, source)
                    ps.setString(2, externalId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
        if (current !=
            null
        ) {
            return if (current == fighterId) null else "External id is already linked to fighter $current"
        }
        conn
            .prepareStatement(
                "INSERT INTO fighter_external_id (fighter_id, source, external_id) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.setString(2, source)
                ps.setString(3, externalId)
                ps.executeUpdate()
            }
        return null
    }

    private fun finish(
        conn: Connection,
        id: Long,
        reviewStatus: String,
        admin: String,
        eventId: Long,
        eventStatus: IngestStatus,
    ): ResolveResult {
        conn
            .prepareStatement("UPDATE review_item SET status = ?, resolved_by = ?, resolved_at = now() WHERE id = ?")
            .use { ps ->
                ps.setString(1, reviewStatus)
                ps.setString(2, admin)
                ps.setLong(3, id)
                ps.executeUpdate()
            }
        conn
            .prepareStatement(
                "UPDATE ingest_event SET status = ?, " +
                    "applied_at = CASE WHEN ? = 'APPLIED' THEN now() ELSE applied_at END WHERE id = ?",
            ).use { ps ->
                ps.setString(1, eventStatus.name)
                ps.setString(2, eventStatus.name)
                ps.setLong(3, eventId)
                ps.executeUpdate()
            }
        return ResolveResult.Done
    }

    private fun lock(
        conn: Connection,
        id: Long,
    ): Item? =
        conn
            .prepareStatement(
                "SELECT r.reason, r.status, e.id, e.source, e.external_key, e.type, e.payload::text, e.status " +
                    "FROM review_item r JOIN ingest_event e ON e.id = r.ingest_event_id WHERE r.id = ? FOR UPDATE OF r",
            ).use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        Item(
                            rs.getString(1),
                            rs.getString(2),
                            StoredIngestEvent(
                                rs.getLong(3),
                                rs.getString(4),
                                rs.getString(5),
                                IngestEventType.fromWireName(rs.getString(6)),
                                rs.getString(7),
                                IngestStatus.valueOf(rs.getString(8)),
                            ),
                        )
                    }
                }
            }

    private fun HandlerOutcome.toStatus() =
        when (this) {
            HandlerOutcome.APPLIED -> IngestStatus.APPLIED
            HandlerOutcome.NEEDS_REVIEW -> IngestStatus.NEEDS_REVIEW
            HandlerOutcome.REJECTED -> IngestStatus.REJECTED
        }
}
