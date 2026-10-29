package com.fightexchange.rules.sim

import com.fightexchange.rules.Elo
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Port of the strategies in sim/sim.py; names match the Python STRATS list. */
object Strategies {
    private fun days(from: LocalDate, to: LocalDate) = ChronoUnit.DAYS.between(from, to)

    private fun Market.priceM(name: String) = price(name).toMillions()

    private fun activePool(mk: Market, today: LocalDate, maxWeeks: Int = 52) =
        mk.last.entries
            .filter { (n, d) -> days(d, today) < maxWeeks * 7 && n !in mk.delisted }
            .map { it.key }

    private fun impliedR(f: Fight) = (1 / f.ro!!) / (1 / f.ro + 1 / f.bo!!)

    private fun expDiv(mk: Market, f: Fight, side: Char): Double {
        val o = f.odds(side) ?: return 0.0
        val pr = (1 / o) / (1 / f.ro!! + 1 / f.bo!!)
        return pr * (1.2 * minOf(o, mk.rules.oddsCap) + 0.4)
    }

    private fun fillHold(pl: Player, today: LocalDate, pool: List<String>, k: Int) {
        for (n in pool) {
            if (pl.hold.size >= k) break
            pl.buy(n, today)
        }
    }

    private fun affordable(pl: Player, pool: List<String>, k: Int) =
        pool.filter { pl.mk.priceM(it) < pl.cash.toMillions() / maxOf(1, k - pl.hold.size) }

    private fun pickCaptain(pl: Player, wf: List<Fight>) {
        var best: String? = null
        var bv = -1.0
        for (f in wf) {
            for (s in "RB") {
                if (f.name(s) in pl.hold && expDiv(pl.mk, f, s) > bv) {
                    best = f.name(s)
                    bv = expDiv(pl.mk, f, s)
                }
            }
        }
        pl.captain = best
    }

    val cash: Strategy = { _, _, _, _ -> }

    val randomHold: Strategy = { pl, today, _, _ ->
        if (pl.hold.size < 12) {
            val pool = activePool(pl.mk, today).toMutableList()
            pl.rnd.shuffle(pool)
            fillHold(pl, today, affordable(pl, pool, 12), 12)
        }
        pl.captain = null
    }

    val bluechip: Strategy = { pl, today, _, wf ->
        if (pl.hold.size < 8) {
            val pool = activePool(pl.mk, today).sortedBy { -pl.mk.priceM(it) }
            fillHold(pl, today, affordable(pl, pool, 8), 8)
        }
        pickCaptain(pl, wf)
    }

    val activeHold: Strategy = { pl, today, _, wf ->
        val mk = pl.mk
        if (pl.hold.size < 12) {
            val cnt = LinkedHashMap<String, Int>()
            for (f in mk.pre) {
                if (f.date.year == 2024) {
                    cnt.merge(f.r, 1, Int::plus)
                    cnt.merge(f.b, 1, Int::plus)
                }
            }
            val pool =
                cnt.entries
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .filter { it !in mk.delisted }
            fillHold(pl, today, affordable(pl, pool, 12), 12)
        }
        pickCaptain(pl, wf)
    }

    private fun rent(pl: Player, today: LocalDate, wf: List<Fight>, key: (Market, Fight, Char) -> Double) {
        val mk = pl.mk
        if (wf.isEmpty()) return
        val fighting = LinkedHashMap<String, Pair<Fight, Char>>()
        for (f in wf) for (s in "RB") if (f.ro != null) fighting[f.name(s)] = f to s
        pl.hold.keys
            .filter { it !in fighting }
            .take(mk.opt.maxTradesWeek)
            .forEach(pl::sell)
        val cands = fighting.keys.sortedBy { n -> fighting.getValue(n).let { (f, s) -> key(mk, f, s) } }
        var buys = 0
        for (n in cands) {
            if (pl.hold.size >= mk.rules.maxRoster || buys >= mk.opt.maxTradesWeek) break
            if (n in pl.hold) continue
            if (pl.buy(n, today)) buys++
        }
        pickCaptain(pl, wf)
    }

    val announced: Strategy = { pl, today, ann, wf ->
        val mk = pl.mk
        val lim = mk.opt.maxTradesWeek
        pl.hold.keys
            .filter { it !in ann }
            .take(lim)
            .forEach(pl::sell)
        val cands =
            ann.keys
                .filter { it !in pl.hold }
                .sortedBy { n ->
                    val f = ann.getValue(n).first
                    -expDiv(mk, f, if (f.r == n) 'R' else 'B') / mk.priceM(n)
                }.sortedBy { n -> -days(today, ann.getValue(n).first.date) }
        var buys = 0
        for (n in cands) {
            if (buys >= lim || pl.hold.size >= mk.rules.maxRoster) break
            if (pl.buy(n, today)) buys++
        }
        pickCaptain(pl, wf)
    }

    private fun due(
        pl: Player,
        today: LocalDate,
        ann: Map<String, Pair<Fight, String>>,
        wf: List<Fight>,
        shorts: Boolean,
    ) {
        val mk = pl.mk
        val lim = minOf(2, mk.opt.maxTradesWeek)
        val done =
            pl.hold.entries
                .filter { (n, bought) ->
                    n !in ann && days(mk.last[n] ?: today, today) < 8 && days(bought, today) > 7
                }.map { it.key }
        done.take(lim).forEach(pl::sell)
        val due =
            mk.last.entries
                .filter { (n, d) -> days(d, today) in 70..140 && n !in mk.delisted && n !in ann }
                .map { it.key }
                .sortedBy { mk.priceM(it) }
        var buys = 0
        for (n in due) {
            if (buys >= lim || pl.hold.size >= mk.rules.maxRoster) break
            if (pl.buy(n, today)) buys++
        }
        if (shorts) {
            pl.short.keys
                .filter { it !in ann && days(mk.last[it] ?: today, today) < 8 }
                .forEach(pl::closeShort)
            val weak = due.filter { it !in pl.hold }.sortedBy { mk.elo(it) }
            weak.take(3).forEach { pl.openShort(it, today) }
        }
        pickCaptain(pl, wf)
    }

    val due: Strategy = { pl, today, ann, wf -> due(pl, today, ann, wf, shorts = false) }

    val dueShort: Strategy = { pl, today, ann, wf -> due(pl, today, ann, wf, shorts = true) }

    private val yieldKey = { mk: Market, f: Fight, s: Char -> -expDiv(mk, f, s) / mk.priceM(f.name(s)) }

    val rentYield: Strategy = { pl, today, _, wf -> rent(pl, today, wf, yieldKey) }

    val rentFav: Strategy = { pl, today, _, wf -> rent(pl, today, wf) { _, f, s -> f.odds(s)!! } }

    val rentArb: Strategy = { pl, today, _, wf ->
        rent(pl, today, wf) { mk, f, s ->
            val pr = impliedR(f)
            val pe = Elo.expected(mk.elo(f.r), mk.elo(f.b))
            -(if (s == 'R') pr - pe else pe - pr)
        }
    }

    val rentYieldShort: Strategy = { pl, today, ann, wf ->
        val mk = pl.mk
        pl.short.keys
            .toList()
            .forEach(pl::closeShort)
        rentYield(pl, today, ann, wf)
        val cands = mutableListOf<Pair<Double, String>>()
        for (f in wf) {
            if (f.ro == null) continue
            val pr = impliedR(f)
            val pe = Elo.expected(mk.elo(f.r), mk.elo(f.b))
            cands += (pr - pe) to f.r
            cands += (pe - pr) to f.b
        }
        cands.sortedWith(compareBy({ it.first }, { it.second })).take(3).forEach { (_, n) ->
            if (n !in pl.hold) pl.openShort(n, today)
        }
    }

    val all: List<Pair<String, Strategy>> =
        listOf(
            "hotovost (nic nedelat)" to cash,
            "nahodne portfolio 12, drzet" to randomHold,
            "hvezdy 8, drzet" to bluechip,
            "nejaktivnejsi 2024, drzet" to activeHold,
            "drzet jen ohlasene zapasniky" to announced,
            "predvidat ohlaseni (10-20 tydnu po zapase)" to due,
            "predvidat ohlaseni + 3 shorty" to dueShort,
            "pronajem: levni zapasnici tydne" to rentYield,
            "pronajem: favorite tydne" to rentFav,
            "pronajem: Elo vs kurzy" to rentArb,
            "pronajem levnych + 3 shorty" to rentYieldShort,
        )
}
