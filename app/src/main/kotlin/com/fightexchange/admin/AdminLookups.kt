package com.fightexchange.admin

import com.fightexchange.ingest.ADMIN_SOURCE
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection

/** How an existing event or bout is addressed by ingest payloads. */
data class SourceRef(
    val id: Long,
    val source: String,
    val externalId: String,
)

@Singleton
class AdminLookups(
    private val tx: TransactionOperations<Connection>,
) {
    fun event(id: Long): SourceRef? = ref("SELECT id, source, external_id FROM event WHERE id = ?", id)

    fun bout(id: Long): SourceRef? = ref("SELECT id, source, external_id FROM bout WHERE id = ?", id)

    /** The bout, only if it belongs to [eventId]. */
    fun boutOfEvent(
        id: Long,
        eventId: Long,
    ): SourceRef? =
        tx.executeRead { status ->
            status.connection
                .prepareStatement("SELECT id, source, external_id FROM bout WHERE id = ? AND event_id = ?")
                .use { ps ->
                    ps.setLong(1, id)
                    ps.setLong(2, eventId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) SourceRef(rs.getLong(1), rs.getString(2), rs.getString(3)) else null
                    }
                }
        }

    fun fighterExists(id: Long): Boolean = exists("SELECT 1 FROM fighter WHERE id = ? AND merged_into_id IS NULL", id)

    /** Id of the row an admin event created, found through the generated external key. */
    fun createdFighter(key: String): Long? =
        scalar("SELECT fighter_id FROM fighter_external_id WHERE source = ? AND external_id = ?", key)

    fun createdEvent(key: String): Long? = scalar("SELECT id FROM event WHERE source = ? AND external_id = ?", key)

    fun createdBout(key: String): Long? = scalar("SELECT id FROM bout WHERE source = ? AND external_id = ?", key)

    private fun ref(
        sql: String,
        id: Long,
    ): SourceRef? =
        tx.executeRead { status ->
            status.connection.prepareStatement(sql).use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next() &&
                        rs.getString(3) != null
                    ) {
                        SourceRef(rs.getLong(1), rs.getString(2), rs.getString(3))
                    } else {
                        null
                    }
                }
            }
        }

    private fun exists(
        sql: String,
        id: Long,
    ): Boolean =
        tx.executeRead { status ->
            status.connection.prepareStatement(sql).use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { it.next() }
            }
        }

    private fun scalar(
        sql: String,
        key: String,
    ): Long? =
        tx.executeRead { status ->
            status.connection.prepareStatement(sql).use { ps ->
                ps.setString(1, ADMIN_SOURCE)
                ps.setString(2, key)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
        }
}
