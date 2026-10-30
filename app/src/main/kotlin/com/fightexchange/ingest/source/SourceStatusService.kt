package com.fightexchange.ingest.source

import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class SourceStatus(
    val source: String,
    val lastSuccessAt: Instant?,
    val lastError: String?,
    val lastErrorAt: Instant?,
    val creditsLeft: Int?,
)

/** A source that needs the admin's attention, with the reason in plain text. */
data class SourceAlert(
    val source: String,
    val reason: String,
)

@Singleton
class SourceStatusService(
    private val tx: TransactionOperations<Connection>,
    private val clock: Clock,
) {
    /** Marks a successful sync; a reported credit count replaces the stored one, otherwise it is kept. */
    fun recordSuccess(
        source: String,
        creditsLeft: Int?,
    ) {
        tx.executeWrite { status ->
            status.connection
                .prepareStatement(
                    """
                    INSERT INTO source_status (source, last_success_at, credits_left)
                    VALUES (?, ?, ?)
                    ON CONFLICT (source) DO UPDATE SET last_success_at = excluded.last_success_at,
                        credits_left = COALESCE(excluded.credits_left, source_status.credits_left),
                        updated_at = excluded.last_success_at
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, source)
                    ps.setTimestamp(2, Timestamp.from(clock.instant()))
                    ps.setObject(3, creditsLeft)
                    ps.executeUpdate()
                }
        }
    }

    fun recordError(
        source: String,
        message: String,
    ) {
        tx.executeWrite { status ->
            status.connection
                .prepareStatement(
                    """
                    INSERT INTO source_status (source, last_error, last_error_at) VALUES (?, ?, ?)
                    ON CONFLICT (source) DO UPDATE SET last_error = excluded.last_error,
                        last_error_at = excluded.last_error_at, updated_at = excluded.last_error_at
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, source)
                    ps.setString(2, message.take(MAX_ERROR_LENGTH))
                    ps.setTimestamp(3, Timestamp.from(clock.instant()))
                    ps.executeUpdate()
                }
        }
    }

    fun list(): List<SourceStatus> =
        tx.executeRead { status ->
            status.connection.createStatement().use { s ->
                s
                    .executeQuery(
                        "SELECT source, last_success_at, last_error, last_error_at, credits_left " +
                            "FROM source_status ORDER BY source",
                    ).use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    SourceStatus(
                                        rs.getString("source"),
                                        rs.getTimestamp("last_success_at")?.toInstant(),
                                        rs.getString("last_error"),
                                        rs.getTimestamp("last_error_at")?.toInstant(),
                                        rs.getObject("credits_left") as Int?,
                                    ),
                                )
                            }
                        }
                    }
            }
        }

    /**
     * Sources with no success for longer than [maxSilence] (since their first error when they never succeeded)
     * or with at most [minCredits] credits left.
     */
    fun alerts(
        maxSilence: Duration,
        minCredits: Int,
    ): List<SourceAlert> {
        val now = clock.instant()
        return list().flatMap { s ->
            val since = s.lastSuccessAt ?: s.lastErrorAt
            buildList {
                if (since != null && s.lastError != null && Duration.between(since, now) > maxSilence) {
                    add(SourceAlert(s.source, "no successful sync since $since (${s.lastError})"))
                }
                if (s.creditsLeft != null && s.creditsLeft <= minCredits) {
                    add(SourceAlert(s.source, "only ${s.creditsLeft} API credits left"))
                }
            }
        }
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 500
    }
}
