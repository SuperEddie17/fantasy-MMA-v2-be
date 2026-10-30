package com.fightexchange.market

import com.fightexchange.rules.Money
import com.fightexchange.rules.Pricing
import com.fightexchange.rules.RuleSet
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

enum class PriceEventKind {
    ELO,
    ANNOUNCED,
    OPPONENT_BONUS,
    CANCELLED,
    RANKING,
    ODDS_DRIFT,
    INACTIVITY,
    DEMAND,
    RESULT_RESET,
    DELIST,
}

/** What caused a price change; stored on the `price_event` row. */
data class PriceCause(
    val kind: PriceEventKind,
    val at: Instant,
    val boutId: Long? = null,
    val ingestEventId: Long? = null,
    val note: String? = null,
)

data class Listing(
    val fighterId: Long,
    val status: String,
    val baseValue: Money,
    val eventMultiplier: Double,
    val demandFactor: Double,
    val price: Money,
)

/**
 * Keeps `market_listing` consistent: price = base value (from Elo) x event multiplier x demand factor,
 * and logs every change with its reason in `price_event`. All methods run on the caller's transaction connection.
 */
@Singleton
class MarketService {
    /** Latest Elo of the fighter, or [RuleSet.eloStart] for a debutant. */
    fun currentElo(
        conn: Connection,
        fighterId: Long,
        rules: RuleSet = RuleSet.V2,
    ): Double =
        conn
            .prepareStatement(
                "SELECT rating_after FROM elo_rating WHERE fighter_id = ? ORDER BY rated_at DESC, id DESC LIMIT 1",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getDouble(1) else rules.eloStart }
            }

    fun listing(
        conn: Connection,
        fighterId: Long,
    ): Listing? =
        conn
            .prepareStatement(
                "SELECT fighter_id, status, base_value, event_multiplier, demand_factor, price " +
                    "FROM market_listing WHERE fighter_id = ?",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        Listing(
                            rs.getLong(1),
                            rs.getString(2),
                            Money(rs.getLong(3)),
                            rs.getDouble(4),
                            rs.getDouble(5),
                            Money(rs.getLong(6)),
                        )
                    } else {
                        null
                    }
                }
            }

    /**
     * IPO: creates the listing of a new fighter priced from their current Elo (a debutant starts at `eloStart`).
     * Returns false and changes nothing when the fighter is already listed.
     */
    fun ensureListed(
        conn: Connection,
        fighterId: Long,
        cause: PriceCause,
        rules: RuleSet = RuleSet.V2,
    ): Boolean {
        if (listing(conn, fighterId) != null) return false
        val base = Pricing.basePrice(currentElo(conn, fighterId, rules), rules)
        conn
            .prepareStatement(
                "INSERT INTO market_listing (fighter_id, status, base_value, price, listed_at) " +
                    "VALUES (?, 'IPO', ?, ?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.setLong(2, base.units)
                ps.setLong(3, base.units)
                ps.setTimestamp(4, Timestamp.from(cause.at))
                ps.executeUpdate()
            }
        logEvent(conn, fighterId, cause.copy(note = cause.note ?: "IPO"), null, base, base)
        return true
    }

    /**
     * Applies a new [baseValue] and/or a relative change of the event multiplier ([multiplierFactor], 1.0 = none)
     * or sets it outright ([resetMultiplier]), recomputes the price and logs a `price_event`.
     * A delisted fighter is left alone. Returns the new price, or null when nothing was changed.
     */
    fun apply(
        conn: Connection,
        fighterId: Long,
        cause: PriceCause,
        multiplierFactor: Double = 1.0,
        baseValue: Money? = null,
        resetMultiplier: Double? = null,
        rules: RuleSet = RuleSet.V2,
    ): Money? {
        val current = listing(conn, fighterId)?.takeIf { it.status != "DELISTED" } ?: return null
        val base = baseValue ?: current.baseValue
        val multiplier = resetMultiplier ?: (current.eventMultiplier * multiplierFactor)
        val price = Pricing.price(base, multiplier, current.demandFactor, rules)
        conn
            .prepareStatement(
                "UPDATE market_listing SET base_value = ?, event_multiplier = ?, price = ?, " +
                    "status = CASE WHEN status = 'IPO' THEN 'ACTIVE' ELSE status END WHERE fighter_id = ?",
            ).use { ps ->
                ps.setLong(1, base.units)
                ps.setDouble(2, multiplier)
                ps.setLong(3, price.units)
                ps.setLong(4, fighterId)
                ps.executeUpdate()
            }
        val factor = if (current.price.units == 0L) null else price.toDouble() / current.price.toDouble()
        logEvent(conn, fighterId, cause, factor, current.price, price)
        return price
    }

    private fun logEvent(
        conn: Connection,
        fighterId: Long,
        cause: PriceCause,
        factor: Double?,
        before: Money?,
        after: Money,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO price_event (fighter_id, at, kind, factor, price_before, price_after, " +
                    "bout_id, ingest_event_id, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.setTimestamp(2, Timestamp.from(cause.at))
                ps.setString(3, cause.kind.name)
                if (factor == null) ps.setNull(4, java.sql.Types.NUMERIC) else ps.setDouble(4, factor)
                ps.setLong(5, (before ?: after).units)
                ps.setLong(6, after.units)
                if (cause.boutId == null) ps.setNull(7, java.sql.Types.BIGINT) else ps.setLong(7, cause.boutId)
                if (cause.ingestEventId == null) {
                    ps.setNull(8, java.sql.Types.BIGINT)
                } else {
                    ps.setLong(8, cause.ingestEventId)
                }
                ps.setString(9, cause.note)
                ps.executeUpdate()
            }
    }
}
