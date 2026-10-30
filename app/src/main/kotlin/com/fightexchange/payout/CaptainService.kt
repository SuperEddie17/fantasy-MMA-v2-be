package com.fightexchange.payout

import com.fightexchange.common.ApiException
import io.micronaut.http.HttpStatus
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

@Singleton
class CaptainService(
    private val tx: TransactionOperations<Connection>,
) {
    /**
     * Sets the captain of [portfolioId] for [eventId], replacing an earlier pick. The fighter must be held long
     * and fight on the event's card; picks close at the weigh-in (the event start when no weigh-in is known).
     */
    fun pick(
        portfolioId: Long,
        eventId: Long,
        fighterId: Long?,
        now: Instant,
    ): CaptainPickDto {
        if (fighterId == null) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request", "fighterId is required")
        }
        return tx.executeWrite { status ->
            val conn = status.connection
            lockPortfolio(conn, portfolioId)
            requireOpen(conn, eventId, now)
            requireEligible(conn, portfolioId, eventId, fighterId)
            conn
                .prepareStatement(
                    "INSERT INTO captain_pick (portfolio_id, event_id, fighter_id, picked_at) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT (portfolio_id, event_id) DO UPDATE SET fighter_id = EXCLUDED.fighter_id, " +
                        "picked_at = EXCLUDED.picked_at, locked_at = NULL",
                ).use { ps ->
                    ps.setLong(1, portfolioId)
                    ps.setLong(2, eventId)
                    ps.setLong(3, fighterId)
                    ps.setTimestamp(4, Timestamp.from(now))
                    ps.executeUpdate()
                }
            picks(conn, portfolioId, eventId).single()
        }
    }

    /** The portfolio's picks, newest event first. */
    fun list(portfolioId: Long): List<CaptainPickDto> = tx.executeRead { picks(it.connection, portfolioId, null) }

    /** Locks every pick whose event weigh-in has passed; returns the number locked. Idempotent. */
    fun lockDue(now: Instant): Int =
        tx.executeWrite { status ->
            status.connection
                .prepareStatement(
                    "UPDATE captain_pick c SET locked_at = ? FROM event e WHERE e.id = c.event_id " +
                        "AND c.locked_at IS NULL AND COALESCE(e.weigh_in_at, e.starts_at) <= ?",
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp.from(now))
                    ps.setTimestamp(2, Timestamp.from(now))
                    ps.executeUpdate()
                }
        }

    private fun requireOpen(
        conn: Connection,
        eventId: Long,
        now: Instant,
    ) {
        val lockAt = lockTime(conn, eventId)
        if (lockAt == null || lockAt.first in CLOSED_EVENT) {
            throw ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Event is not open for captain picks")
        }
        if (!now.isBefore(lockAt.second)) {
            throw ApiException(HttpStatus.CONFLICT, "CAPTAIN_LOCKED", "Captain picks for this event are closed")
        }
    }

    private fun requireEligible(
        conn: Connection,
        portfolioId: Long,
        eventId: Long,
        fighterId: Long,
    ) {
        if (!onCard(conn, eventId, fighterId)) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "FIGHTER_NOT_ON_CARD",
                "The fighter has no open bout on this event",
            )
        }
        if (!heldLong(conn, portfolioId, fighterId)) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "FIGHTER_NOT_HELD",
                "The captain must be a fighter you hold",
            )
        }
    }

    private fun lockPortfolio(
        conn: Connection,
        portfolioId: Long,
    ) {
        conn.prepareStatement("SELECT id FROM portfolio WHERE id = ? FOR UPDATE").use { ps ->
            ps.setLong(1, portfolioId)
            ps.executeQuery().use { it.next() }
        }
    }

    /** Event status and the instant picks close. */
    private fun lockTime(
        conn: Connection,
        eventId: Long,
    ): Pair<String, Instant>? =
        conn
            .prepareStatement("SELECT status, COALESCE(weigh_in_at, starts_at) FROM event WHERE id = ?")
            .use { ps ->
                ps.setLong(1, eventId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) to rs.getTimestamp(2).toInstant() else null
                }
            }

    private fun onCard(
        conn: Connection,
        eventId: Long,
        fighterId: Long,
    ): Boolean =
        conn
            .prepareStatement(
                "SELECT 1 FROM bout WHERE event_id = ? AND status IN ('ANNOUNCED', 'CONFIRMED', 'NEEDS_REVIEW') " +
                    "AND (fighter_a_id = ? OR fighter_b_id = ?)",
            ).use { ps ->
                ps.setLong(1, eventId)
                ps.setLong(2, fighterId)
                ps.setLong(3, fighterId)
                ps.executeQuery().use { it.next() }
            }

    private fun heldLong(
        conn: Connection,
        portfolioId: Long,
        fighterId: Long,
    ): Boolean =
        conn
            .prepareStatement(
                "SELECT 1 FROM position_lot WHERE portfolio_id = ? AND fighter_id = ? AND side = 'LONG' " +
                    "AND closed_at IS NULL",
            ).use { ps ->
                ps.setLong(1, portfolioId)
                ps.setLong(2, fighterId)
                ps.executeQuery().use { it.next() }
            }

    private fun picks(
        conn: Connection,
        portfolioId: Long,
        eventId: Long?,
    ): List<CaptainPickDto> =
        conn
            .prepareStatement(
                "SELECT c.event_id, c.fighter_id, f.name, c.picked_at, c.locked_at IS NOT NULL " +
                    "FROM captain_pick c JOIN fighter f ON f.id = c.fighter_id WHERE c.portfolio_id = ? " +
                    (if (eventId != null) "AND c.event_id = ? " else "") + "ORDER BY c.event_id DESC",
            ).use { ps ->
                ps.setLong(1, portfolioId)
                if (eventId != null) ps.setLong(2, eventId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                CaptainPickDto(
                                    rs.getLong(1),
                                    rs.getLong(2),
                                    rs.getString(3),
                                    rs.getTimestamp(4).toInstant(),
                                    rs.getBoolean(5),
                                ),
                            )
                        }
                    }
                }
            }

    private companion object {
        val CLOSED_EVENT = setOf("COMPLETED", "CANCELLED")
    }
}
