package com.fightexchange.rules.sim

import com.fightexchange.rules.BoutOutcome
import com.fightexchange.rules.BoutType
import com.fightexchange.rules.Dividend
import com.fightexchange.rules.DividendBonuses
import com.fightexchange.rules.Eligibility
import com.fightexchange.rules.Elo
import com.fightexchange.rules.FightOutcome
import com.fightexchange.rules.FightResult
import com.fightexchange.rules.Inactivity
import com.fightexchange.rules.Money
import com.fightexchange.rules.Pricing
import com.fightexchange.rules.RuleSet
import com.fightexchange.rules.TradeCost
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Kotlin port of the season loop in sim/sim.py (`Market`, `Player`, `run`), driven by the `rules` module.
 * Order of iteration, ties and RNG follow the Python code so the results can be compared one to one.
 * Options that rules v2 fixes (yield dividends, short fix, no both opponents) are always on.
 */
data class Fight(
    val date: LocalDate,
    val r: String,
    val b: String,
    val ro: Double?,
    val bo: Double?,
    /** 'R', 'B' or null (draw / no contest). */
    val win: Char?,
    val finish: Boolean,
    val rnd: Int,
    val title: Boolean,
    val main: Boolean,
    val nc: Boolean,
    val bonus: Boolean,
) {
    fun name(side: Char) = if (side == 'R') r else b

    fun odds(side: Char) = if (side == 'R') ro else bo

    fun other(side: Char) = if (side == 'R') 'B' else 'R'
}

/** Sim-only knobs that are not part of the game's rule set. */
data class SimOptions(
    val annWeeks: Long = 6,
    val maxTradesWeek: Int = 99,
    val seasonStart: LocalDate = LocalDate.of(2024, 12, 31),
    val seasonEnd: LocalDate = LocalDate.of(2025, 12, 31),
    val firstWeekEnd: LocalDate = LocalDate.of(2025, 1, 7),
)

class Market(allFights: List<Fight>, val rules: RuleSet, val opt: SimOptions) {
    val elo = HashMap<String, Double>()
    val last = LinkedHashMap<String, LocalDate>()
    val mult = HashMap<String, Double>()
    val pre = allFights.filter { it.date.year < 2025 }
    val fights = allFights.filter { it.date.year == 2025 }
    val delisted = HashSet<String>()
    var ann = LinkedHashMap<String, Pair<Fight, String>>()

    init {
        pre.forEach(::applyElo)
    }

    fun elo(name: String) = elo.getOrPut(name) { rules.eloStart }

    private fun mult(name: String) = mult.getOrPut(name) { 1.0 }

    fun applyElo(f: Fight) {
        val a = elo(f.r)
        val b = elo(f.b)
        last[f.r] = f.date
        last[f.b] = f.date
        val outcome =
            when {
                f.nc -> BoutOutcome.NO_CONTEST
                f.win == 'R' -> BoutOutcome.A_WIN
                f.win == 'B' -> BoutOutcome.B_WIN
                else -> BoutOutcome.DRAW
            }
        val r = Elo.update(a, b, outcome, f.finish, rules)
        elo[f.r] = r.a
        elo[f.b] = r.b
    }

    fun basePrice(name: String) = Pricing.basePrice(elo(name), rules)

    fun price(name: String): Money = Pricing.price(basePrice(name), mult(name), rules = rules)

    fun announcedAt(f: Fight) = f.date.minusWeeks(opt.annWeeks)

    private fun upcoming(today: LocalDate) =
        fights.filter { !it.date.isBefore(today) && it.date.isBefore(today.plusWeeks(opt.annWeeks)) }

    fun weeklyUpdate(today: LocalDate, prev: LocalDate): LinkedHashMap<String, Pair<Fight, String>> {
        val ann = LinkedHashMap<String, Pair<Fight, String>>()
        for (f in upcoming(today)) {
            ann[f.r] = f to f.b
            ann[f.b] = f to f.r
        }
        for (f in fights) {
            val a = announcedAt(f)
            if (prev < a && a <= today) {
                for (s in "RB") {
                    val type =
                        if (f.title) {
                            BoutType.TITLE
                        } else if (f.main) {
                            BoutType.MAIN
                        } else {
                            BoutType.NORMAL
                        }
                    val diff = elo(f.name(f.other(s))) - elo(f.name(s))
                    mult[f.name(s)] = mult(f.name(s)) * (1 + Pricing.announcementBump(type, diff, rules))
                }
            }
        }
        for ((name, lastFight) in last.entries.toList()) {
            val weeks = ChronoUnit.DAYS.between(lastFight, today) / 7.0
            val announced = name in ann
            if (announced) continue
            if (Inactivity.shouldDelist(weeks, announced, rules)) {
                delisted += name
            } else {
                mult[name] = mult(name) * Inactivity.weeklyFactor(weeks, announced, rules)
            }
        }
        return ann
    }

    fun fightWeekDrift(f: Fight) {
        if (f.ro == null || f.bo == null) return
        mult[f.r] = mult(f.r) * Pricing.oddsDrift(f.ro, f.bo, elo(f.r), elo(f.b), rules)
        mult[f.b] = mult(f.b) * Pricing.oddsDrift(f.bo, f.ro, elo(f.b), elo(f.r), rules)
    }

    fun resetMult(name: String) {
        mult[name] = 1.0
    }

    fun result(f: Fight, side: Char) =
        when {
            f.nc || f.win == null -> FightResult.NO_CONTEST
            f.win == side -> FightResult.WIN
            else -> FightResult.LOSS
        }

    fun dividend(f: Fight, side: Char, captain: Boolean, price: Money): Money =
        Dividend
            .forWinner(
                FightOutcome(result(f, side), f.finish, f.rnd),
                f.odds(side),
                price,
                captain,
                DividendBonuses(fightNight = f.bonus, titleWon = f.title),
                rules,
            ).total

    fun shortPayoff(f: Fight, side: Char): Money =
        Dividend.forShort(
            result(f, side),
            f.odds(side),
            f.odds(f.other(side)),
            rules,
        )
}

typealias Strategy = (Player, LocalDate, Map<String, Pair<Fight, String>>, List<Fight>) -> Unit

class Player(val name: String, val mk: Market, val strat: Strategy, seed: Int) {
    var cash = mk.rules.budget
    val hold = LinkedHashMap<String, LocalDate>()
    val short = LinkedHashMap<String, LocalDate>()
    val rnd = PyRandom(seed)
    var div = Money.ZERO
    var fees = Money.ZERO
    var trades = 0
    var captain: String? = null
    var weekBuys = 0

    fun buy(n: String, today: LocalDate): Boolean {
        val cost =
            TradeCost.buy(
                mk.price(n),
                weekBuys,
                firstWeek = !today.isAfter(mk.opt.firstWeekEnd),
                rules = mk.rules,
            )
        val opponent = mk.ann[n]?.second
        if (opponent != null && opponent in hold) return false
        if (n in hold || cost.total > cash || hold.size >= mk.rules.maxRoster || n in mk.delisted) return false
        cash -= cost.total
        fees += cost.fee + cost.extraFee
        weekBuys++
        hold[n] = today
        trades++
        return true
    }

    fun sell(n: String) {
        val s = TradeCost.sell(mk.price(n), mk.rules)
        cash += s.net
        fees += s.fee
        hold.remove(n)
        trades++
    }

    fun openShort(n: String, today: LocalDate) {
        if (short.size >= mk.rules.maxShorts || n in short || n in hold) return
        mk.ann[n]?.let { (f, _) ->
            val o = if (f.r == n) f.ro else f.bo
            if (o != null && o > mk.rules.oddsCap) return
        }
        val s = TradeCost.shortOpen(mk.price(n), mk.rules)
        cash += s.net
        fees += s.fee
        short[n] = today
    }

    fun closeShort(n: String) {
        val c = TradeCost.shortClose(mk.price(n), mk.rules)
        cash -= c.total
        fees += c.fee
        short.remove(n)
    }

    fun worth(): Money {
        var w = cash
        hold.keys.forEach { w += mk.price(it) }
        short.keys.forEach { w -= mk.price(it) }
        return w
    }
}

/** Mirrors sim.py's day-based eligibility: bought at the start of a day, announced at the end of it. */
private fun eligible(boughtOn: LocalDate, announcedOn: LocalDate) =
    Eligibility.dividendEligible(
        boughtOn.atStartOfDay().toInstant(ZoneOffset.UTC),
        announcedOn.atTime(23, 59, 59).toInstant(ZoneOffset.UTC),
    )

fun runSeason(
    fights: List<Fight>,
    strategies: List<Pair<String, Strategy>>,
    rules: RuleSet,
    opt: SimOptions,
    seeds: Int,
): Pair<Market, List<Player>> {
    val mk = Market(fights, rules, opt)
    val players =
        strategies.flatMap { (name, strat) ->
            (0 until if (name.startsWith("nahodne")) seeds else 1).map { Player(name, mk, strat, it) }
        }
    var today = opt.seasonStart
    var prev = today.minusDays(7)
    while (!today.isAfter(opt.seasonEnd)) {
        val ann = mk.weeklyUpdate(today, prev)
        mk.ann = ann
        val weekFights = mk.fights.filter { !it.date.isBefore(today) && it.date.isBefore(today.plusDays(7)) }
        weekFights.forEach(mk::fightWeekDrift)
        for (pl in players) {
            pl.hold.keys
                .filter { it in mk.delisted }
                .forEach(pl::sell)
            pl.weekBuys = 0
            pl.strat(pl, today, ann, weekFights)
        }
        for (f in weekFights) {
            for (pl in players) {
                val active = pl.hold.size >= rules.minRoster
                for (side in "RB") {
                    val n = f.name(side)
                    val boughtOn = pl.hold[n]
                    if (boughtOn != null && active && eligible(boughtOn, mk.announcedAt(f))) {
                        val d = mk.dividend(f, side, pl.captain == n, mk.price(n))
                        pl.cash += d
                        pl.div += d
                    }
                    val shortOn = pl.short[n]
                    if (shortOn != null && eligible(shortOn, mk.announcedAt(f))) {
                        val d = mk.shortPayoff(f, side)
                        pl.cash += d
                        pl.div += d
                    }
                }
            }
            mk.applyElo(f)
            mk.resetMult(f.r)
            mk.resetMult(f.b)
        }
        prev = today
        today = today.plusDays(7)
    }
    return mk to players
}
