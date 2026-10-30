package com.fightexchange.trading

import com.fightexchange.common.ApiException
import com.fightexchange.rules.Eligibility
import com.fightexchange.rules.Lot
import com.fightexchange.rules.Money
import com.fightexchange.rules.RosterAction
import com.fightexchange.rules.RosterFighter
import com.fightexchange.rules.RosterRules
import com.fightexchange.rules.RuleSet
import com.fightexchange.rules.TradeCost
import io.micronaut.http.HttpStatus
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Statement
import java.sql.Timestamp
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

enum class TradeKind(
    val opensLong: Boolean = false,
    val closesLong: Boolean = false,
    val opensShort: Boolean = false,
    val closesShort: Boolean = false,
) {
    BUY(opensLong = true),
    SELL(closesLong = true),
    SHORT_OPEN(opensShort = true),
    SHORT_CLOSE(closesShort = true),
    ;

    val side: String get() = if (opensLong || closesLong) "LONG" else "SHORT"
    val opens: Boolean get() = opensLong || opensShort
}

private const val MAX_KEY = 100

/** The priced trade before it is booked. [cashChange] is signed. */
private class Evaluation(
    val quote: QuoteDto,
    val price: Money,
    val fee: Money,
    val extraFee: Money,
    val cashChange: Money,
    val weekStart: LocalDate,
)

/**
 * Buying, selling and shorting (plan.md, F3.4). A trade runs in one transaction that first locks the portfolio row,
 * so concurrent requests on one portfolio are serialized and cash can never be spent twice.
 * Cash is booked as ledger entries and mirrored in `portfolio.cash_cached` in the same transaction.
 */
@Singleton
class TradingService(
    private val tx: TransactionOperations<Connection>,
    private val reads: TradingReads,
    private val seasons: SeasonService,
    private val ruleSets: RuleSets,
    private val clock: Clock,
) {
    /** Price and limits check without trading; limit breaches are listed in the answer, not thrown. */
    fun quote(
        portfolioId: Long,
        request: TradeRequest,
    ): QuoteDto {
        val kind = kindOf(request)
        val fighterId = fighterOf(request)
        return tx.executeRead { status ->
            val state = state(status.connection, portfolioId, forUpdate = false)
            evaluate(status.connection, portfolioId, state, kind, fighterId, clock.instant()).quote
        }
    }

    fun trade(
        portfolioId: Long,
        request: TradeRequest,
    ): TradeResultDto {
        val kind = kindOf(request)
        val fighterId = fighterOf(request)
        val key = request.idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key != null && key.length > MAX_KEY) invalid("idempotencyKey is at most $MAX_KEY characters")
        return tx.executeWrite { status ->
            val conn = status.connection
            val state = state(conn, portfolioId, forUpdate = true)
            val previous = key?.let { stored(conn, portfolioId, it) }
            if (previous != null) return@executeWrite replay(previous, kind, fighterId, state.cash)
            val now = clock.instant()
            val evaluation = evaluate(conn, portfolioId, state, kind, fighterId, now)
            refuseIfNotAllowed(evaluation.quote)
            val trade = book(conn, portfolioId, kind, evaluation, key, now)
            TradeResultDto(trade, state.cash + evaluation.cashChange.units, replayed = false)
        }
    }

    private fun state(
        conn: Connection,
        portfolioId: Long,
        forUpdate: Boolean,
    ): PortfolioState =
        reads.portfolio(conn, portfolioId, forUpdate)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "PORTFOLIO_NOT_FOUND", "Portfolio does not exist")

    private fun replay(
        previous: TradeDto,
        kind: TradeKind,
        fighterId: Long,
        cash: Long,
    ): TradeResultDto {
        if (previous.kind != kind.name || previous.fighterId != fighterId) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "IDEMPOTENCY_KEY_REUSED",
                "The idempotency key belongs to a different trade",
            )
        }
        return TradeResultDto(previous, cash, replayed = true)
    }

    private fun evaluate(
        conn: Connection,
        portfolioId: Long,
        state: PortfolioState,
        kind: TradeKind,
        fighterId: Long,
        now: Instant,
    ): Evaluation {
        val season = seasons.find(conn, state.seasonId)!!
        val rules = ruleSets.at(season.startsAt)
        val fighter =
            reads.fighter(conn, fighterId)
                ?: throw ApiException(HttpStatus.NOT_FOUND, "FIGHTER_NOT_FOUND", "Fighter is not on the market")
        val weekStart = weekStart(now)
        val buys = reads.buysInWeek(conn, portfolioId, weekStart)
        val firstWeek = weekStart == weekStart(season.startsAt)
        val price = Money(fighter.price)
        val (fee, extra, cashChange) = cost(kind, price, buys, firstWeek, rules)
        val found = mutableListOf<Pair<String, String>>()
        found += windowViolations(season, now)
        if (!fighter.tradable) found += "FIGHTER_NOT_TRADABLE" to "Fighter is not tradable (delisted)"
        if (reads.locked(conn, fighterId, now)) {
            found += "TRADING_LOCKED" to "Trading is closed from the weigh-in until the result is confirmed"
        }
        found += rosterViolations(conn, portfolioId, kind, fighter, rules).map { it.name to describe(it.name) }
        if (state.cash + cashChange.units < 0) {
            found += "INSUFFICIENT_CASH" to "Not enough cash: need ${-cashChange.units}, have ${state.cash}"
        }
        val quote =
            QuoteDto(
                kind.name,
                fighterId,
                fighter.name,
                price.units,
                fee.units,
                extra.units,
                kind == TradeKind.BUY && extra == Money.ZERO,
                cashChange.units,
                state.cash + cashChange.units,
                buys,
                maxOf(0, rules.freeBuysPerWeek - buys),
                firstWeek,
                found.isEmpty(),
                found.map { ViolationDto(it.first, it.second) },
            )
        return Evaluation(quote, price, fee, extra, cashChange, weekStart)
    }

    private fun cost(
        kind: TradeKind,
        price: Money,
        buys: Int,
        firstWeek: Boolean,
        rules: RuleSet,
    ): Triple<Money, Money, Money> =
        when (kind) {
            TradeKind.BUY -> TradeCost.buy(price, buys, firstWeek, rules).let { Triple(it.fee, it.extraFee, -it.total) }
            TradeKind.SELL -> TradeCost.sell(price, rules).let { Triple(it.fee, Money.ZERO, it.net) }
            TradeKind.SHORT_OPEN -> TradeCost.shortOpen(price, rules).let { Triple(it.fee, Money.ZERO, it.net) }
            TradeKind.SHORT_CLOSE -> TradeCost.shortClose(price, rules).let { Triple(it.fee, Money.ZERO, -it.total) }
        }

    private fun windowViolations(
        season: SeasonDto,
        now: Instant,
    ): List<Pair<String, String>> =
        when {
            now < season.startsAt -> listOf("SEASON_NOT_STARTED" to "The season has not started yet")
            !now.isBefore(season.endsAt) -> listOf("SEASON_ENDED" to "The season has ended")
            else -> emptyList()
        }

    private fun rosterViolations(
        conn: Connection,
        portfolioId: Long,
        kind: TradeKind,
        fighter: FighterInfo,
        rules: RuleSet,
    ) = RosterRules.validate(reads.roster(conn, portfolioId), action(conn, kind, fighter), rules)

    private fun action(
        conn: Connection,
        kind: TradeKind,
        fighter: FighterInfo,
    ): RosterAction {
        if (kind.closesLong) return RosterAction.Sell(fighter.id)
        if (kind.closesShort) return RosterAction.CloseShort(fighter.id)
        val bout = reads.announcedBout(conn, fighter.id)
        val subject = RosterFighter(fighter.id, fighter.ufc, bout?.opponentId, bout?.odds)
        return if (kind.opensLong) RosterAction.Buy(subject) else RosterAction.OpenShort(subject)
    }

    private fun refuseIfNotAllowed(quote: QuoteDto) {
        val first = quote.violations.firstOrNull() ?: return
        val status = if (first.code == "TRADING_LOCKED") HttpStatus.CONFLICT else HttpStatus.UNPROCESSABLE_ENTITY
        throw ApiException(status, first.code, "Trade refused", quote.violations.joinToString("; ") { it.message })
    }

    /** Writes the trade, its ledger entries, the lot change, the weekly counter and the cash mirror. */
    private fun book(
        conn: Connection,
        portfolioId: Long,
        kind: TradeKind,
        ev: Evaluation,
        key: String?,
        now: Instant,
    ): TradeDto {
        val fighterId = ev.quote.fighterId
        val tradeId = insertTrade(conn, portfolioId, kind, ev, key, now)
        val sign = if (kind == TradeKind.BUY || kind == TradeKind.SHORT_CLOSE) -1 else 1
        ledger(conn, portfolioId, now, "TRADE", sign * ev.price.units, tradeId)
        ledger(conn, portfolioId, now, "FEE", -ev.fee.units, tradeId)
        if (ev.extraFee > Money.ZERO) ledger(conn, portfolioId, now, "EXTRA_FEE", -ev.extraFee.units, tradeId)
        if (kind.opens) {
            openLot(
                conn,
                portfolioId,
                fighterId,
                kind,
                ev.price,
                now,
                tradeId,
            )
        } else {
            closeLot(conn, portfolioId, fighterId, kind, ev.price, now, tradeId)
        }
        if (kind == TradeKind.BUY) countBuy(conn, portfolioId, ev.weekStart)
        conn.prepareStatement("UPDATE portfolio SET cash_cached = cash_cached + ? WHERE id = ?").use { ps ->
            ps.setLong(1, ev.cashChange.units)
            ps.setLong(2, portfolioId)
            ps.executeUpdate()
        }
        return TradeDto(
            tradeId,
            fighterId,
            ev.quote.fighterName,
            kind.name,
            ev.price.units,
            ev.fee.units,
            ev.extraFee.units,
            ev.quote.freeBuy,
            now,
        )
    }

    private fun insertTrade(
        conn: Connection,
        portfolioId: Long,
        kind: TradeKind,
        ev: Evaluation,
        key: String?,
        now: Instant,
    ): Long =
        conn
            .prepareStatement(
                "INSERT INTO trade (portfolio_id, fighter_id, kind, price, fee, extra_fee, free_buy, at, " +
                    "idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, portfolioId)
                ps.setLong(2, ev.quote.fighterId)
                ps.setString(3, kind.name)
                ps.setLong(4, ev.price.units)
                ps.setLong(5, ev.fee.units)
                ps.setLong(6, ev.extraFee.units)
                ps.setBoolean(7, ev.quote.freeBuy)
                ps.setTimestamp(8, Timestamp.from(now))
                ps.setString(9, key)
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }

    private fun ledger(
        conn: Connection,
        portfolioId: Long,
        at: Instant,
        kind: String,
        amount: Long,
        tradeId: Long,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO ledger_entry (portfolio_id, at, kind, amount, trade_id) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, portfolioId)
                ps.setTimestamp(2, Timestamp.from(at))
                ps.setString(3, kind)
                ps.setLong(4, amount)
                ps.setLong(5, tradeId)
                ps.executeUpdate()
            }
    }

    private fun openLot(
        conn: Connection,
        portfolioId: Long,
        fighterId: Long,
        kind: TradeKind,
        price: Money,
        now: Instant,
        tradeId: Long,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO position_lot (portfolio_id, fighter_id, side, opened_at, open_price, open_trade_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, portfolioId)
                ps.setLong(2, fighterId)
                ps.setString(3, kind.side)
                ps.setTimestamp(4, Timestamp.from(now))
                ps.setLong(5, price.units)
                ps.setLong(6, tradeId)
                ps.executeUpdate()
            }
    }

    /** Closes the oldest open lot of the fighter on that side (FIFO). */
    private fun closeLot(
        conn: Connection,
        portfolioId: Long,
        fighterId: Long,
        kind: TradeKind,
        price: Money,
        now: Instant,
        tradeId: Long,
    ) {
        val lots =
            conn
                .prepareStatement(
                    "SELECT id, opened_at FROM position_lot WHERE portfolio_id = ? AND fighter_id = ? " +
                        "AND side = ? AND closed_at IS NULL",
                ).use { ps ->
                    ps.setLong(1, portfolioId)
                    ps.setLong(2, fighterId)
                    ps.setString(3, kind.side)
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(Lot(rs.getLong(1), rs.getTimestamp(2).toInstant())) }
                    }
                }
        val lot = Eligibility.lotsToClose(lots, 1).single()
        conn
            .prepareStatement(
                "UPDATE position_lot SET closed_at = ?, close_price = ?, close_trade_id = ? WHERE id = ?",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.setLong(2, price.units)
                ps.setLong(3, tradeId)
                ps.setLong(4, lot.id)
                ps.executeUpdate()
            }
    }

    private fun countBuy(
        conn: Connection,
        portfolioId: Long,
        weekStart: LocalDate,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO weekly_buy_counter (portfolio_id, week_start, buys) VALUES (?, ?, 1) " +
                    "ON CONFLICT (portfolio_id, week_start) DO UPDATE SET buys = weekly_buy_counter.buys + 1",
            ).use { ps ->
                ps.setLong(1, portfolioId)
                ps.setObject(2, weekStart)
                ps.executeUpdate()
            }
    }

    private fun stored(
        conn: Connection,
        portfolioId: Long,
        key: String,
    ): TradeDto? =
        conn
            .prepareStatement(
                "SELECT t.id, t.fighter_id, f.name, t.kind, t.price, t.fee, t.extra_fee, t.free_buy, t.at " +
                    "FROM trade t JOIN fighter f ON f.id = t.fighter_id " +
                    "WHERE t.portfolio_id = ? AND t.idempotency_key = ?",
            ).use { ps ->
                ps.setLong(1, portfolioId)
                ps.setString(2, key)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        TradeDto(
                            rs.getLong(1),
                            rs.getLong(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getLong(5),
                            rs.getLong(6),
                            rs.getLong(7),
                            rs.getBoolean(8),
                            rs.getTimestamp(9).toInstant(),
                        )
                    } else {
                        null
                    }
                }
            }

    private fun kindOf(request: TradeRequest): TradeKind =
        TradeKind.entries.firstOrNull { it.name == request.kind?.trim()?.uppercase() }
            ?: invalid("kind must be one of ${TradeKind.entries.joinToString()}")

    private fun fighterOf(request: TradeRequest): Long = request.fighterId ?: invalid("fighterId is required")

    private fun invalid(detail: String): Nothing =
        throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request", detail)

    private fun describe(code: String) =
        when (code) {
            "ROSTER_FULL" -> "At most 15 fighters can be held long"
            "TOO_MANY_SHORTS" -> "At most 3 shorts can be open"
            "ALREADY_HELD" -> "This fighter is already held"
            "NOT_HELD" -> "This fighter is not held"
            "LONG_AND_SHORT_SAME_FIGHTER" -> "A fighter cannot be held long and short at once"
            "BOTH_OPPONENTS" -> "Both opponents of an announced bout cannot be held"
            "MIN_NON_UFC" -> "At least 2 non-UFC fighters must stay on the roster"
            "SHORT_ODDS_TOO_HIGH" -> "Shorting a fighter with announced odds above the cap is not allowed"
            else -> code
        }

    companion object {
        /** Monday of the game week containing [instant] (weeks start Monday 00:00 UTC). */
        fun weekStart(instant: Instant): LocalDate =
            instant.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}
