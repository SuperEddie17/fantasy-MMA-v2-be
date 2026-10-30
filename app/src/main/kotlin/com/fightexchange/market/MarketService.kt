package com.fightexchange.market

import com.fightexchange.rules.BoutType
import com.fightexchange.rules.Money
import com.fightexchange.rules.Pricing
import com.fightexchange.rules.RuleSet
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.math.round

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

    /**
     * Brings the announcement bumps of a bout in line with its current fighters, type and the Elo of both sides:
     * a new bout adds `bump(type) + stronger-opponent bump` to each fighter's multiplier, a changed one only the
     * difference, and a fighter who left the bout gets their bump back. Each part is logged as its own price event.
     */
    fun syncBout(
        conn: Connection,
        boutId: Long,
        cause: PriceCause,
        rules: RuleSet = RuleSet.V2,
    ) {
        val bout = boutSides(conn, boutId)
        val applied = bumps(conn, boutId)
        val current =
            if (bout.status == "CANCELLED" || bout.status == "COMPLETED") {
                emptyMap()
            } else {
                val elo = listOf(bout.fighterA, bout.fighterB).associateWith { currentElo(conn, it, rules) }
                val diff = elo.getValue(bout.fighterB) - elo.getValue(bout.fighterA)
                mapOf(
                    bout.fighterA to bumpFor(bout.type, diff, rules),
                    bout.fighterB to bumpFor(bout.type, -diff, rules),
                )
            }
        for (fighterId in applied.keys + current.keys) {
            val before = applied[fighterId] ?: Bump(0.0, 0.0)
            val after = current[fighterId] ?: Bump(0.0, 0.0)
            ensureListed(conn, fighterId, cause.copy(boutId = null, note = null), rules)
            val c = cause.copy(boutId = boutId)
            val note = if (after.total == 0.0) "bout no longer applies" else null
            if (after.base != before.base) {
                val factor = (1 + after.base) / (1 + before.base)
                apply(
                    conn,
                    fighterId,
                    c.copy(kind = PriceEventKind.ANNOUNCED, note = c.note ?: note),
                    factor,
                    rules = rules,
                )
            }
            if (after.opp != before.opp) {
                val factor = (1 + after.opp) / (1 + before.opp)
                apply(
                    conn,
                    fighterId,
                    c.copy(kind = PriceEventKind.OPPONENT_BONUS, note = c.note ?: note),
                    factor,
                    rules = rules,
                )
            }
            storeBump(conn, boutId, fighterId, after.takeIf { it.total > 0.0 })
        }
    }

    /**
     * A cancelled bout: both sides lose its announcement bump and the fighter who dropped out also falls by
     * `cancelDropMax` for an injury and `cancelDropMin` otherwise. Call it once, when the bout turns cancelled.
     */
    fun cancelBout(
        conn: Connection,
        boutId: Long,
        withdrawn: Long?,
        reason: String?,
        cause: PriceCause,
        rules: RuleSet = RuleSet.V2,
    ) {
        syncBout(conn, boutId, cause, rules)
        driftBout(conn, boutId, cause.copy(note = cause.note ?: "bout cancelled"), rules)
        if (withdrawn == null) return
        val drop =
            if (reason?.contains("injur", ignoreCase = true) ==
                true
            ) {
                rules.cancelDropMax
            } else {
                rules.cancelDropMin
            }
        apply(
            conn,
            withdrawn,
            cause.copy(kind = PriceEventKind.CANCELLED, boutId = boutId, note = cause.note ?: reason),
            1.0 - drop,
            rules = rules,
        )
    }

    /**
     * Fight-week odds drift: for an open bout whose latest odds were taken within `driftWindow` of the event start,
     * each fighter's multiplier carries `Pricing.oddsDrift` (odds probability against Elo expectation). A newer
     * snapshot moves only the difference to what is applied; a bout that is cancelled, completed or has no odds
     * in the window gets its drift taken back.
     */
    fun driftBout(
        conn: Connection,
        boutId: Long,
        cause: PriceCause,
        rules: RuleSet = RuleSet.V2,
    ) {
        val bout = boutSides(conn, boutId)
        val applied = drifts(conn, boutId)
        val target =
            if (bout.status == "CANCELLED" ||
                bout.status == "COMPLETED"
            ) {
                emptyMap()
            } else {
                targetDrift(conn, boutId, bout, rules)
            }
        for (fighterId in applied.keys + target.keys) {
            val before = applied[fighterId] ?: 1.0
            val after = target[fighterId] ?: 1.0
            if (after == before) continue
            ensureListed(conn, fighterId, cause.copy(boutId = null, note = null), rules)
            apply(
                conn,
                fighterId,
                cause.copy(kind = PriceEventKind.ODDS_DRIFT, boutId = boutId),
                after / before,
                rules = rules,
            )
            storeDrift(conn, boutId, fighterId, after.takeIf { it != 1.0 })
        }
    }

    private fun targetDrift(
        conn: Connection,
        boutId: Long,
        bout: BoutSides,
        rules: RuleSet,
    ): Map<Long, Double> {
        val odds = latestOddsInWindow(conn, boutId) ?: return emptyMap()
        val eloA = currentElo(conn, bout.fighterA, rules)
        val eloB = currentElo(conn, bout.fighterB, rules)
        return mapOf(
            bout.fighterA to round6(Pricing.oddsDrift(odds.first, odds.second, eloA, eloB, rules)),
            bout.fighterB to round6(Pricing.oddsDrift(odds.second, odds.first, eloB, eloA, rules)),
        )
    }

    /** (odds A, odds B) of the newest snapshot taken at most [DRIFT_WINDOW] before the event starts. */
    private fun latestOddsInWindow(
        conn: Connection,
        boutId: Long,
    ): Pair<Double, Double>? =
        conn
            .prepareStatement(
                "SELECT o.odds_a, o.odds_b FROM odds_snapshot o JOIN bout b ON b.id = o.bout_id " +
                    "JOIN event e ON e.id = b.event_id WHERE o.bout_id = ? " +
                    "AND o.taken_at >= e.starts_at - make_interval(days => ?) " +
                    "AND o.taken_at <= e.starts_at ORDER BY o.taken_at DESC, o.id DESC LIMIT 1",
            ).use { ps ->
                ps.setLong(1, boutId)
                ps.setInt(2, DRIFT_WINDOW.toDays().toInt())
                ps.executeQuery().use { rs -> if (rs.next()) rs.getDouble(1) to rs.getDouble(2) else null }
            }

    /**
     * A finished bout: its announcement bump and drift are gone, so each fighter's multiplier falls back to what
     * their other open bouts still add (1 when none) and the base value follows the new Elo. Fighters whose Elo
     * moved only because a later bout was recalculated get the new base value as well.
     */
    fun settleBout(
        conn: Connection,
        boutId: Long,
        cause: PriceCause,
        rules: RuleSet = RuleSet.V2,
    ) {
        val bout = boutSides(conn, boutId)
        for (table in listOf("bout_price_bump", "bout_price_drift")) {
            conn.prepareStatement("DELETE FROM $table WHERE bout_id = ?").use { ps ->
                ps.setLong(1, boutId)
                ps.executeUpdate()
            }
        }
        for (fighterId in listOf(bout.fighterA, bout.fighterB)) {
            ensureListed(conn, fighterId, cause.copy(boutId = null, note = null), rules)
            refresh(
                conn,
                fighterId,
                cause.copy(kind = PriceEventKind.RESULT_RESET),
                resetMultiplier(conn, fighterId),
                rules,
            )
            markFought(conn, fighterId, boutId)
        }
        val later =
            conn
                .prepareStatement(
                    "SELECT DISTINCT r.fighter_id FROM elo_rating r WHERE r.rated_at >= " +
                        "(SELECT e.starts_at FROM bout b JOIN event e ON e.id = b.event_id WHERE b.id = ?)",
                ).use { ps ->
                    ps.setLong(1, boutId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
                }
        for (fighterId in later - setOf(bout.fighterA, bout.fighterB)) {
            val note = "Elo recalculated"
            refresh(conn, fighterId, cause.copy(kind = PriceEventKind.ELO, note = cause.note ?: note), null, rules)
        }
    }

    /** Moves the base value to the current Elo (and the multiplier to [multiplier]); logs only a real change. */
    private fun refresh(
        conn: Connection,
        fighterId: Long,
        cause: PriceCause,
        multiplier: Double?,
        rules: RuleSet,
    ) {
        val current = listing(conn, fighterId)?.takeIf { it.status != "DELISTED" } ?: return
        val base = Pricing.basePrice(currentElo(conn, fighterId, rules), rules)
        val mult = multiplier?.let(::round6)
        if (base == current.baseValue && (mult == null || mult == round6(current.eventMultiplier))) return
        apply(conn, fighterId, cause, baseValue = base, resetMultiplier = mult, rules = rules)
    }

    /** Product of the bumps and drifts that the fighter's other open bouts still carry. */
    private fun resetMultiplier(
        conn: Connection,
        fighterId: Long,
    ): Double =
        conn
            .prepareStatement(
                "SELECT COALESCE(EXP(SUM(LN(1 + base_bump) + LN(1 + opp_bump))), 1) FROM bout_price_bump " +
                    "WHERE fighter_id = ?",
            ).use { ps ->
                ps.setLong(1, fighterId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getDouble(1)
                }
            } *
            conn
                .prepareStatement("SELECT COALESCE(EXP(SUM(LN(factor))), 1) FROM bout_price_drift WHERE fighter_id = ?")
                .use { ps ->
                    ps.setLong(1, fighterId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getDouble(1)
                    }
                }

    private fun markFought(
        conn: Connection,
        fighterId: Long,
        boutId: Long,
    ) {
        conn
            .prepareStatement(
                "UPDATE market_listing SET last_fight_at = GREATEST(COALESCE(last_fight_at, '-infinity'), " +
                    "(SELECT e.starts_at FROM bout b JOIN event e ON e.id = b.event_id WHERE b.id = ?)) " +
                    "WHERE fighter_id = ?",
            ).use { ps ->
                ps.setLong(1, boutId)
                ps.setLong(2, fighterId)
                ps.executeUpdate()
            }
    }

    /**
     * A ranking snapshot of [division]: every fighter whose rank changed since the previous snapshot of that
     * division moves by `Pricing.rankingFactor`. The first snapshot of a division only sets the baseline.
     * Call it once per snapshot, after its rows are stored.
     */
    fun applyRanking(
        conn: Connection,
        division: String,
        takenAt: Instant,
        cause: PriceCause,
        rules: RuleSet = RuleSet.V2,
    ) {
        val previousAt =
            conn
                .prepareStatement(
                    "SELECT max(taken_at) FROM ranking_snapshot WHERE division = ? AND taken_at < ?",
                ).use { ps ->
                    ps.setString(1, division)
                    ps.setTimestamp(2, Timestamp.from(takenAt))
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getTimestamp(1)?.toInstant()
                    }
                } ?: return
        val previous = ranks(conn, division, previousAt)
        val current = ranks(conn, division, takenAt)
        for (fighterId in (previous.keys + current.keys).sorted()) {
            val factor = Pricing.rankingFactor(previous[fighterId], current[fighterId], rules)
            if (factor == 1.0 || listing(conn, fighterId) == null) continue
            val note = "$division: ${previous[fighterId] ?: "unranked"} -> ${current[fighterId] ?: "unranked"}"
            apply(
                conn,
                fighterId,
                cause.copy(kind = PriceEventKind.RANKING, at = takenAt, note = cause.note ?: note),
                factor,
                rules = rules,
            )
        }
    }

    private fun ranks(
        conn: Connection,
        division: String,
        takenAt: Instant,
    ): Map<Long, Int> =
        conn
            .prepareStatement("SELECT fighter_id, rank FROM ranking_snapshot WHERE division = ? AND taken_at = ?")
            .use { ps ->
                ps.setString(1, division)
                ps.setTimestamp(2, Timestamp.from(takenAt))
                ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getLong(1), rs.getInt(2)) } }
            }

    private fun drifts(
        conn: Connection,
        boutId: Long,
    ): Map<Long, Double> =
        conn
            .prepareStatement("SELECT fighter_id, factor FROM bout_price_drift WHERE bout_id = ?")
            .use { ps ->
                ps.setLong(1, boutId)
                ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getLong(1), rs.getDouble(2)) } }
            }

    private fun storeDrift(
        conn: Connection,
        boutId: Long,
        fighterId: Long,
        factor: Double?,
    ) {
        if (factor == null) {
            conn.prepareStatement("DELETE FROM bout_price_drift WHERE bout_id = ? AND fighter_id = ?").use { ps ->
                ps.setLong(1, boutId)
                ps.setLong(2, fighterId)
                ps.executeUpdate()
            }
            return
        }
        conn
            .prepareStatement(
                "INSERT INTO bout_price_drift (bout_id, fighter_id, factor) VALUES (?, ?, ?) " +
                    "ON CONFLICT (bout_id, fighter_id) DO UPDATE SET factor = EXCLUDED.factor",
            ).use { ps ->
                ps.setLong(1, boutId)
                ps.setLong(2, fighterId)
                ps.setDouble(3, factor)
                ps.executeUpdate()
            }
    }

    private fun round6(v: Double) = round(v * 1_000_000) / 1_000_000

    private companion object {
        val DRIFT_WINDOW: Duration = Duration.ofDays(7)
    }

    private data class Bump(
        val base: Double,
        val opp: Double,
    ) {
        val total get() = base + opp
    }

    private data class BoutSides(
        val fighterA: Long,
        val fighterB: Long,
        val type: BoutType,
        val status: String,
    )

    /** Bump from the bout type, split from the stronger-opponent part (0 when the opponent is not stronger). */
    private fun bumpFor(
        type: BoutType,
        eloDiff: Double,
        rules: RuleSet,
    ): Bump {
        val base = Pricing.announcementBump(type, Double.NEGATIVE_INFINITY, rules)
        return Bump(base, Pricing.announcementBump(type, eloDiff, rules) - base)
    }

    private fun boutSides(
        conn: Connection,
        boutId: Long,
    ): BoutSides =
        conn
            .prepareStatement("SELECT fighter_a_id, fighter_b_id, bout_type, status FROM bout WHERE id = ?")
            .use { ps ->
                ps.setLong(1, boutId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "Unknown bout id $boutId" }
                    BoutSides(rs.getLong(1), rs.getLong(2), BoutType.valueOf(rs.getString(3)), rs.getString(4))
                }
            }

    private fun bumps(
        conn: Connection,
        boutId: Long,
    ): Map<Long, Bump> =
        conn
            .prepareStatement("SELECT fighter_id, base_bump, opp_bump FROM bout_price_bump WHERE bout_id = ?")
            .use { ps ->
                ps.setLong(1, boutId)
                ps.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put(rs.getLong(1), Bump(rs.getDouble(2), rs.getDouble(3))) }
                }
            }

    private fun storeBump(
        conn: Connection,
        boutId: Long,
        fighterId: Long,
        bump: Bump?,
    ) {
        if (bump == null) {
            conn.prepareStatement("DELETE FROM bout_price_bump WHERE bout_id = ? AND fighter_id = ?").use { ps ->
                ps.setLong(1, boutId)
                ps.setLong(2, fighterId)
                ps.executeUpdate()
            }
            return
        }
        conn
            .prepareStatement(
                "INSERT INTO bout_price_bump (bout_id, fighter_id, base_bump, opp_bump) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (bout_id, fighter_id) DO UPDATE SET base_bump = EXCLUDED.base_bump, " +
                    "opp_bump = EXCLUDED.opp_bump",
            ).use { ps ->
                ps.setLong(1, boutId)
                ps.setLong(2, fighterId)
                ps.setDouble(3, bump.base)
                ps.setDouble(4, bump.opp)
                ps.executeUpdate()
            }
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
