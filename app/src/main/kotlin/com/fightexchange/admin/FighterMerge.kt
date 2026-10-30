package com.fightexchange.admin

import com.fightexchange.ingest.EloService
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection

sealed interface MergeResult {
    data object Merged : MergeResult

    data class Rejected(
        val reason: String,
    ) : MergeResult
}

/** Merges a duplicate fighter into another one: history moves to the target and Elo is recalculated. */
@Singleton
class FighterMerge(
    private val tx: TransactionOperations<Connection>,
    private val elo: EloService,
) {
    fun merge(
        duplicateId: Long,
        intoId: Long,
    ): MergeResult =
        tx.executeWrite { status ->
            val conn = status.connection
            val reason = validate(conn, duplicateId, intoId)
            if (reason != null) return@executeWrite MergeResult.Rejected(reason)
            repoint(conn, duplicateId, intoId)
            firstCompletedBout(conn, intoId)?.let { elo.recalculateFrom(conn, it) }
            MergeResult.Merged
        }

    private fun validate(
        conn: Connection,
        duplicateId: Long,
        intoId: Long,
    ): String? {
        if (duplicateId == intoId) return "A fighter cannot be merged into itself"
        val dup = load(conn, duplicateId) ?: return "Fighter $duplicateId does not exist"
        val target = load(conn, intoId) ?: return "Fighter $intoId does not exist"
        return when {
            dup.merged || target.merged -> "Fighter is already merged"
            dup.org != target.org || dup.sex != target.sex -> "Fighters must share organization and sex"
            fought(conn, duplicateId, intoId) -> "Fighters fought each other, they cannot be one person"
            else -> null
        }
    }

    private class Row(
        val org: Long,
        val sex: String,
        val merged: Boolean,
    )

    private fun load(
        conn: Connection,
        id: Long,
    ): Row? =
        conn
            .prepareStatement("SELECT organization_id, sex, merged_into_id IS NOT NULL FROM fighter WHERE id = ?")
            .use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) Row(rs.getLong(1), rs.getString(2), rs.getBoolean(3)) else null
                }
            }

    private fun fought(
        conn: Connection,
        a: Long,
        b: Long,
    ): Boolean =
        conn
            .prepareStatement(
                "SELECT 1 FROM bout WHERE (fighter_a_id = ? AND fighter_b_id = ?) " +
                    "OR (fighter_a_id = ? AND fighter_b_id = ?)",
            ).use { ps ->
                listOf(a, b, b, a).forEachIndexed { i, v -> ps.setLong(i + 1, v) }
                ps.executeQuery().use { it.next() }
            }

    private fun repoint(
        conn: Connection,
        from: Long,
        to: Long,
    ) {
        val statements =
            listOf(
                "UPDATE fighter_external_id SET fighter_id = :to WHERE fighter_id = :from",
                "UPDATE bout SET fighter_a_id = :to WHERE fighter_a_id = :from",
                "UPDATE bout SET fighter_b_id = :to WHERE fighter_b_id = :from",
                "UPDATE bout SET withdrawn_fighter_id = :to WHERE withdrawn_fighter_id = :from",
                "UPDATE bout_result SET winner_id = :to WHERE winner_id = :from",
                "UPDATE bout_bonus SET fighter_id = :to WHERE fighter_id = :from",
                "UPDATE weight_miss SET fighter_id = :to WHERE fighter_id = :from",
                "UPDATE elo_rating SET fighter_id = :to WHERE fighter_id = :from",
                // a snapshot ranking both fighters keeps the target's rank
                "DELETE FROM ranking_snapshot r USING ranking_snapshot t WHERE r.fighter_id = :from " +
                    "AND t.fighter_id = :to AND t.division = r.division AND t.taken_at = r.taken_at",
                "UPDATE ranking_snapshot SET fighter_id = :to WHERE fighter_id = :from",
                "UPDATE fighter SET merged_into_id = :to WHERE id = :from",
            )
        for (sql in statements) {
            conn.prepareStatement(sql.replace(":to", "?").replace(":from", "?")).use { ps ->
                // placeholders appear in textual order, so bind by scanning the original statement
                var index = 1
                Regex(":to|:from").findAll(sql).forEach {
                    ps.setLong(index++, if (it.value == ":to") to else from)
                }
                ps.executeUpdate()
            }
        }
    }

    private fun firstCompletedBout(
        conn: Connection,
        fighterId: Long,
    ): Long? =
        conn
            .prepareStatement(
                "SELECT b.id FROM bout b JOIN event e ON e.id = b.event_id " +
                    "WHERE b.status = 'COMPLETED' AND (b.fighter_a_id = ? OR b.fighter_b_id = ?) " +
                    "ORDER BY e.starts_at, b.id LIMIT 1",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.setLong(2, fighterId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
}
