package com.fightexchange.ingest

import io.micronaut.context.annotation.Property
import jakarta.inject.Singleton
import java.sql.Connection
import java.time.Clock
import java.time.Instant

/** Source used by admin forms; its events are already confirmed by the admin. */
const val ADMIN_SOURCE = "ADMIN"

/** Decides which events from which sources wait for an admin decision (`ingest.confirmation.required-types`). */
@Singleton
class ConfirmationPolicy(
    @Property(name = "ingest.confirmation.required-types") private val requiredTypes: List<String>,
) {
    fun requiresConfirmation(event: StoredIngestEvent): Boolean =
        event.source != ADMIN_SOURCE && event.type.wireName in requiredTypes
}

enum class ReviewReason { FIGHTER_MATCH, CANCELLATION, RESULT_CONFIRM, UNKNOWN_BOUT }

/** A stored bout with the data handlers need to resolve fighters and organization. */
data class BoutRow(
    val id: Long,
    val organizationId: Long,
    val fighterAId: Long,
    val fighterBId: Long,
    val status: String,
)

/** Shared lookups and writes for handlers; every method runs on the processor's transaction connection. */
@Singleton
class IngestSupport(
    private val matcher: FighterMatcher,
    private val clock: Clock,
) {
    /** Time stamped on the effects of the event being handled (market price events, announcement seen). */
    fun now(): Instant = clock.instant()

    fun organizationId(
        conn: Connection,
        code: String,
    ): Long =
        queryLong(conn, "SELECT id FROM organization WHERE code = ?", code)
            ?: throw IllegalArgumentException("Unknown organization: $code")

    fun eventRow(
        conn: Connection,
        source: String,
        externalId: String,
    ): Pair<Long, Long> =
        conn
            .prepareStatement("SELECT id, organization_id FROM event WHERE source = ? AND external_id = ?")
            .use { ps ->
                ps.setString(1, source)
                ps.setString(2, externalId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        rs.getLong(
                            1,
                        ) to rs.getLong(2)
                    } else {
                        error("Unknown event: $externalId")
                    }
                }
            }

    fun boutRow(
        conn: Connection,
        source: String,
        externalId: String,
    ): BoutRow = findBout(conn, source, externalId) ?: error("Unknown bout: $externalId")

    /** Like [boutRow], but `null` for a bout we have never seen. */
    fun findBout(
        conn: Connection,
        source: String,
        externalId: String,
    ): BoutRow? =
        conn
            .prepareStatement(
                "SELECT b.id, e.organization_id, b.fighter_a_id, b.fighter_b_id, b.status FROM bout b " +
                    "JOIN event e ON e.id = b.event_id WHERE b.source = ? AND b.external_id = ?",
            ).use { ps ->
                ps.setString(1, source)
                ps.setString(2, externalId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        BoutRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5))
                    } else {
                        null
                    }
                }
            }

    /** Resolves a reference to a fighter id; `null` when the match is not certain (caller opens a review item). */
    fun resolveFighter(
        conn: Connection,
        source: String,
        organizationId: Long,
        ref: IngestPayload.FighterRef,
    ): Long? = (matcher.match(conn, source, organizationId, ref) as? FighterMatch.Matched)?.fighterId

    /** Like [resolveFighter], but a name that is ambiguous in general may be unambiguous within this bout. */
    fun resolveInBout(
        conn: Connection,
        source: String,
        bout: BoutRow,
        ref: IngestPayload.FighterRef,
    ): Long? =
        when (val match = matcher.match(conn, source, bout.organizationId, ref)) {
            is FighterMatch.Matched -> match.fighterId.takeIf { it == bout.fighterAId || it == bout.fighterBId }
            is FighterMatch.Uncertain ->
                match.candidateIds.filter { it == bout.fighterAId || it == bout.fighterBId }.singleOrNull()
            FighterMatch.NotFound -> null
        }

    fun openReview(
        conn: Connection,
        eventId: Long,
        reason: ReviewReason,
    ) {
        conn.prepareStatement("INSERT INTO review_item (ingest_event_id, reason) VALUES (?, ?)").use { ps ->
            ps.setLong(1, eventId)
            ps.setString(2, reason.name)
            ps.executeUpdate()
        }
    }

    private fun queryLong(
        conn: Connection,
        sql: String,
        arg: String,
    ): Long? =
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, arg)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
}
