package com.fightexchange.ingest

import com.fightexchange.rules.BoutOutcome
import com.fightexchange.rules.Elo
import com.fightexchange.rules.RuleSet
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

/** Writes `elo_rating` rows for completed bouts, in event-time order (ties by bout id). */
@Singleton
class EloService {
    private data class RatedBout(
        val id: Long,
        val startsAt: Instant,
        val fighterA: Long,
        val fighterB: Long,
        val outcome: BoutOutcome,
        val finish: Boolean,
    )

    /**
     * Rates [boutId] and every later bout that depends on it: a changed rating moves both fighters'
     * next opponents' ratings, so a fighter becomes "dirty" once any of their bouts is recalculated.
     * Safe to call again after a corrected result; stale rows of recalculated bouts are replaced.
     */
    fun recalculateFrom(
        conn: Connection,
        boutId: Long,
        rules: RuleSet = RuleSet.V2,
    ) = recalculate(conn, boutId, setOf(boutId), rules)

    /**
     * Rates every bout in [boutIds] (new bouts, e.g. a history import) and, like [recalculateFrom], every later
     * bout whose fighters they touch. Bouts outside the set are only recalculated when a fighter is dirty.
     */
    fun recalculateBouts(
        conn: Connection,
        boutIds: Set<Long>,
        rules: RuleSet = RuleSet.V2,
    ) {
        val first = firstBout(conn, boutIds) ?: return
        recalculate(conn, first, boutIds, rules)
    }

    /** The chronologically first of [boutIds], by (event start, bout id). */
    private fun firstBout(
        conn: Connection,
        boutIds: Set<Long>,
    ): Long? =
        conn
            .prepareStatement(
                "SELECT b.id FROM bout b JOIN event e ON e.id = b.event_id WHERE b.id = ANY (?) " +
                    "ORDER BY e.starts_at, b.id LIMIT 1",
            ).use { ps ->
                ps.setArray(1, conn.createArrayOf("bigint", boutIds.toTypedArray()))
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }

    private fun recalculate(
        conn: Connection,
        boutId: Long,
        forced: Set<Long>,
        rules: RuleSet,
    ) {
        val bouts = ratedBoutsFrom(conn, boutId)
        if (bouts.isEmpty() || bouts.first().id != boutId) return
        val dirty = mutableSetOf<Long>()
        val ratings = mutableMapOf<Long, Double>()
        for (bout in bouts) {
            if (bout.id !in forced && bout.fighterA !in dirty && bout.fighterB !in dirty) continue
            deleteRows(conn, bout.id)
            dirty += bout.fighterA
            dirty += bout.fighterB
            if (bout.outcome != BoutOutcome.NO_CONTEST) rate(conn, bout, ratings, rules)
        }
    }

    private fun rate(
        conn: Connection,
        bout: RatedBout,
        ratings: MutableMap<Long, Double>,
        rules: RuleSet,
    ) {
        val a = ratings.getOrPut(bout.fighterA) { ratingBefore(conn, bout.fighterA, bout, rules) }
        val b = ratings.getOrPut(bout.fighterB) { ratingBefore(conn, bout.fighterB, bout, rules) }
        val after = Elo.update(a, b, bout.outcome, bout.finish, rules)
        insertRow(conn, bout, bout.fighterA, a, after.a)
        insertRow(conn, bout, bout.fighterB, b, after.b)
        ratings[bout.fighterA] = after.a
        ratings[bout.fighterB] = after.b
    }

    /** Rating of [fighterId] after their last rated bout before [bout] (or the start rating). */
    private fun ratingBefore(
        conn: Connection,
        fighterId: Long,
        bout: RatedBout,
        rules: RuleSet,
    ): Double =
        conn
            .prepareStatement(
                "SELECT rating_after FROM elo_rating WHERE fighter_id = ? " +
                    "AND (rated_at, bout_id) < (?, ?) ORDER BY rated_at DESC, bout_id DESC LIMIT 1",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.setTimestamp(2, Timestamp.from(bout.startsAt))
                ps.setLong(3, bout.id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getDouble(1) else rules.eloStart }
            }

    /** Completed bouts with a current result from [boutId] onward, oldest first. */
    private fun ratedBoutsFrom(
        conn: Connection,
        boutId: Long,
    ): List<RatedBout> =
        conn
            .prepareStatement(
                "SELECT b.id, e.starts_at, b.fighter_a_id, b.fighter_b_id, r.outcome, r.winner_id, r.method " +
                    "FROM bout b JOIN event e ON e.id = b.event_id " +
                    "JOIN bout_result r ON r.bout_id = b.id AND r.is_current " +
                    "WHERE b.status = 'COMPLETED' AND (e.starts_at, b.id) >= " +
                    "(SELECT e2.starts_at, b2.id FROM bout b2 JOIN event e2 ON e2.id = b2.event_id WHERE b2.id = ?) " +
                    "ORDER BY e.starts_at, b.id",
            ).use { ps ->
                ps.setLong(1, boutId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val a = rs.getLong(3)
                            val outcome =
                                when (rs.getString(5)) {
                                    "WIN" -> if (rs.getLong(6) == a) BoutOutcome.A_WIN else BoutOutcome.B_WIN
                                    "DRAW" -> BoutOutcome.DRAW
                                    else -> BoutOutcome.NO_CONTEST
                                }
                            add(
                                RatedBout(
                                    rs.getLong(1),
                                    rs.getTimestamp(2).toInstant(),
                                    a,
                                    rs.getLong(4),
                                    outcome,
                                    rs.getString(7) in FINISH_METHODS,
                                ),
                            )
                        }
                    }
                }
            }

    private fun deleteRows(
        conn: Connection,
        boutId: Long,
    ) {
        conn.prepareStatement("DELETE FROM elo_rating WHERE bout_id = ?").use { ps ->
            ps.setLong(1, boutId)
            ps.executeUpdate()
        }
    }

    private fun insertRow(
        conn: Connection,
        bout: RatedBout,
        fighterId: Long,
        before: Double,
        after: Double,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO elo_rating (fighter_id, bout_id, rating_before, rating_after, rated_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.setLong(2, bout.id)
                ps.setDouble(3, before)
                ps.setDouble(4, after)
                ps.setTimestamp(5, Timestamp.from(bout.startsAt))
                ps.executeUpdate()
            }
    }

    private companion object {
        val FINISH_METHODS = setOf("KO_TKO", "SUB")
    }
}
