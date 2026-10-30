package com.fightexchange.trading

import com.fightexchange.common.ApiException
import io.micronaut.http.HttpStatus
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

private const val SEASON_SELECT =
    "SELECT s.id, s.name, s.starts_at, s.ends_at, r.version FROM season s JOIN rule_set r ON r.id = s.rule_set_id"

@Singleton
class SeasonService(
    private val tx: TransactionOperations<Connection>,
) {
    /** The season runs on the rule set valid at its start (latest `valid_from` not after it, else the oldest). */
    fun create(
        name: String?,
        startsAt: Instant?,
        endsAt: Instant?,
    ): SeasonDto {
        val valid = !name.isNullOrBlank() && startsAt != null && endsAt != null && endsAt.isAfter(startsAt)
        if (!valid) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Invalid request",
                "name, startsAt and endsAt (after startsAt) are required",
            )
        }
        requireNotNull(name)
        requireNotNull(startsAt)
        requireNotNull(endsAt)
        return tx.executeWrite { status ->
            val id =
                status.connection
                    .prepareStatement(
                        "INSERT INTO season (name, starts_at, ends_at, rule_set_id) VALUES (?, ?, ?, " +
                            "COALESCE(" +
                            "(SELECT id FROM rule_set WHERE valid_from <= ? ORDER BY valid_from DESC LIMIT 1), " +
                            "(SELECT id FROM rule_set ORDER BY valid_from LIMIT 1))) RETURNING id",
                    ).use { ps ->
                        ps.setString(1, name.trim())
                        ps.setTimestamp(2, Timestamp.from(startsAt))
                        ps.setTimestamp(3, Timestamp.from(endsAt))
                        ps.setTimestamp(4, Timestamp.from(startsAt))
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }
            find(status.connection, id)!!
        }
    }

    fun list(): List<SeasonDto> =
        tx.executeRead { status ->
            status.connection.createStatement().use { s ->
                s.executeQuery("$SEASON_SELECT ORDER BY s.starts_at DESC, s.id DESC").use { rs ->
                    buildList { while (rs.next()) add(season(rs)) }
                }
            }
        }

    fun find(
        conn: Connection,
        id: Long,
    ): SeasonDto? =
        conn.prepareStatement("$SEASON_SELECT WHERE s.id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) season(rs) else null }
        }

    /** The season that contains [now]; the one starting latest if seasons overlap. */
    fun current(
        conn: Connection,
        now: Instant,
    ): SeasonDto? =
        conn
            .prepareStatement(
                "$SEASON_SELECT WHERE s.starts_at <= ? AND s.ends_at > ? ORDER BY s.starts_at DESC LIMIT 1",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.setTimestamp(2, Timestamp.from(now))
                ps.executeQuery().use { rs -> if (rs.next()) season(rs) else null }
            }

    private fun season(rs: ResultSet) =
        SeasonDto(
            rs.getLong(1),
            rs.getString(2),
            rs.getTimestamp(3).toInstant(),
            rs.getTimestamp(4).toInstant(),
            rs.getInt(5),
        )
}
