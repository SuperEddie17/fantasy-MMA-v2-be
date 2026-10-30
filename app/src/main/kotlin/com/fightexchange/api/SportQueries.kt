package com.fightexchange.api

import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant

/** Read-only queries behind the public sport-data endpoints. Merged fighters are never returned. */
@Singleton
class SportQueries(
    private val tx: TransactionOperations<Connection>,
) {
    fun organizations(): List<OrganizationDto> =
        query("SELECT code, name FROM organization ORDER BY id") { OrganizationDto(it.getString(1), it.getString(2)) }

    fun fighters(
        org: String?,
        division: String?,
        q: String?,
        after: Long?,
        limit: Int,
    ): Page<FighterDto> {
        val where = mutableListOf("f.merged_into_id IS NULL")
        val args = mutableListOf<Any>()
        org?.let {
            where += "o.code = ?"
            args += it.uppercase()
        }
        division?.let {
            where += "f.division = ?"
            args += it
        }
        q?.takeIf { it.isNotBlank() }?.let {
            where += "f.normalized_name LIKE ?"
            args += "%" +
                com.fightexchange.ingest.NameNormalizer
                    .normalize(it) + "%"
        }
        after?.let {
            where += "f.id > ?"
            args += it
        }
        val rows =
            query(
                "$FIGHTER_SELECT WHERE ${where.joinToString(" AND ")} ORDER BY f.id LIMIT ?",
                *args.toTypedArray(),
                limit + 1,
            ) { it.fighter(1) }
        return page(rows, limit) { it.id }
    }

    fun fighter(id: Long): FighterDetailDto? {
        val fighter =
            query(
                "$FIGHTER_SELECT WHERE f.id = ? AND f.merged_into_id IS NULL",
                id,
            ) { it.fighter(1) }.firstOrNull()
        fighter ?: return null
        val elo =
            query(
                "SELECT rating_after FROM elo_rating WHERE fighter_id = ? ORDER BY rated_at DESC, bout_id DESC LIMIT 1",
                id,
            ) {
                it.getDouble(1)
            }.firstOrNull()
        val bouts =
            query(
                """
                SELECT b.id, e.id, e.name, e.starts_at, opp.id, opp.name, b.status, r.outcome, r.winner_id, r.method
                FROM bout b
                JOIN event e ON e.id = b.event_id
                JOIN fighter opp ON opp.id = CASE WHEN b.fighter_a_id = ? THEN b.fighter_b_id ELSE b.fighter_a_id END
                LEFT JOIN bout_result r ON r.bout_id = b.id AND r.is_current
                WHERE (b.fighter_a_id = ? OR b.fighter_b_id = ?) AND b.status <> 'CANCELLED'
                ORDER BY e.starts_at DESC, b.id DESC LIMIT ?
                """.trimIndent(),
                id,
                id,
                id,
                RECENT_BOUTS,
            ) {
                val outcome = it.getString(8)
                val winner = it.getLong(9).takeUnless { _ -> it.wasNull() }
                FighterBoutDto(
                    boutId = it.getLong(1),
                    eventId = it.getLong(2),
                    eventName = it.getString(3),
                    startsAt = it.getTimestamp(4).toInstant(),
                    opponentId = it.getLong(5),
                    opponentName = it.getString(6),
                    status = it.getString(7),
                    result =
                        when {
                            outcome == null -> null
                            outcome == "WIN" -> if (winner == id) "WIN" else "LOSS"
                            else -> outcome
                        },
                    method = it.getString(10),
                )
            }
        return FighterDetailDto(fighter, elo, bouts)
    }

    fun events(
        from: Instant?,
        to: Instant?,
        org: String?,
        after: Long?,
        limit: Int,
    ): Page<EventDto> {
        val where = mutableListOf("TRUE")
        val args = mutableListOf<Any>()
        from?.let {
            where += "e.starts_at >= ?"
            args += java.sql.Timestamp.from(it)
        }
        to?.let {
            where += "e.starts_at < ?"
            args += java.sql.Timestamp.from(it)
        }
        org?.let {
            where += "o.code = ?"
            args += it.uppercase()
        }
        after?.let {
            where += "e.id > ?"
            args += it
        }
        val rows =
            query(
                "$EVENT_SELECT WHERE ${where.joinToString(" AND ")} ORDER BY e.id LIMIT ?",
                *args.toTypedArray(),
                limit + 1,
            ) { it.event() }
        return page(rows, limit) { it.id }
    }

    fun event(id: Long): EventDetailDto? {
        val event = query("$EVENT_SELECT WHERE e.id = ?", id) { it.event() }.firstOrNull() ?: return null
        val bouts =
            query(
                """
                SELECT b.id, b.bout_type, b.status, b.announced_at, a.id, b.fighter_b_id,
                       r.outcome, r.winner_id, r.method, r.round, r.time, r.title_won
                FROM bout b
                JOIN fighter a ON a.id = b.fighter_a_id
                LEFT JOIN bout_result r ON r.bout_id = b.id AND r.is_current
                WHERE b.event_id = ?
                ORDER BY CASE b.bout_type WHEN 'TITLE' THEN 0 WHEN 'MAIN' THEN 1 ELSE 2 END, b.id
                """.trimIndent(),
                id,
            ) {
                BoutRow(
                    id = it.getLong(1),
                    boutType = it.getString(2),
                    status = it.getString(3),
                    announcedAt = it.getTimestamp(4)?.toInstant(),
                    fighterAId = it.getLong(5),
                    fighterBId = it.getLong(6),
                    result =
                        it.getString(7)?.let { outcome ->
                            BoutResultDto(
                                outcome,
                                it.getLong(8).takeUnless { _ -> it.wasNull() },
                                it.getString(9),
                                it.getInt(10).takeUnless { _ -> it.wasNull() },
                                it.getString(11),
                                it.getBoolean(12),
                            )
                        },
                )
            }
        val fighters =
            bouts
                .flatMap { listOf(it.fighterAId, it.fighterBId) }
                .distinct()
                .associateWith { fighterById(it) }
        return EventDetailDto(
            event,
            bouts.map {
                BoutDto(
                    it.id,
                    fighters.getValue(it.fighterAId),
                    fighters.getValue(it.fighterBId),
                    it.boutType,
                    it.status,
                    it.announcedAt,
                    it.result,
                )
            },
        )
    }

    // Merged fighters can still appear on old bouts, so this lookup does not filter them out.
    private fun fighterById(
        id: Long,
    ): FighterDto = query("$FIGHTER_SELECT WHERE f.id = ?", id) { it.fighter(1) }.single()

    private class BoutRow(
        val id: Long,
        val boutType: String,
        val status: String,
        val announcedAt: Instant?,
        val fighterAId: Long,
        val fighterBId: Long,
        val result: BoutResultDto?,
    )

    private fun <T> page(
        rows: List<T>,
        limit: Int,
        id: (T) -> Long,
    ): Page<T> = if (rows.size > limit) Page(rows.take(limit), id(rows[limit - 1])) else Page(rows, null)

    private fun ResultSet.fighter(start: Int) =
        FighterDto(
            getLong(start),
            getString(start + 1),
            getString(start + 2),
            getString(start + 3),
            getString(start + 4),
            getString(start + 5),
            getString(start + 6),
        )

    private fun ResultSet.event() =
        EventDto(
            getLong(1),
            getString(2),
            getString(3),
            getTimestamp(4).toInstant(),
            getDate(5)?.toLocalDate(),
            getTimestamp(6)?.toInstant(),
            getString(7),
        )

    private fun <T> query(
        sql: String,
        vararg args: Any,
        map: (ResultSet) -> T,
    ): List<T> =
        tx.executeRead { st ->
            st.connection.prepareStatement(sql).use { ps ->
                ps.bind(args)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) map(rs) else null }.toList() }
            }
        }

    private fun PreparedStatement.bind(args: Array<out Any>) = args.forEachIndexed { i, a -> setObject(i + 1, a) }

    private companion object {
        const val RECENT_BOUTS = 10
        const val FIGHTER_SELECT =
            "SELECT f.id, f.name, f.nickname, f.sex, f.division, o.code, f.country " +
                "FROM fighter f JOIN organization o ON o.id = f.organization_id"
        const val EVENT_SELECT =
            "SELECT e.id, o.code, e.name, e.starts_at, e.local_date, e.weigh_in_at, e.status " +
                "FROM event e JOIN organization o ON o.id = e.organization_id"
    }
}
