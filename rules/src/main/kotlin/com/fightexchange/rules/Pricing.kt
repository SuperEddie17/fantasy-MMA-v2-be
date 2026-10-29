package com.fightexchange.rules

import kotlin.math.exp

enum class BoutType { NORMAL, MAIN, TITLE }

object Pricing {
    /** Base value of a fighter from Elo: priceAt1500 * e^((elo - 1500) / priceScale), at least priceMin. */
    fun basePrice(elo: Double, rules: RuleSet = RuleSet.V2): Money =
        maxOf(rules.priceMin, Money.ofUnits(rules.priceAt1500.toDouble() * exp((elo - 1500.0) / rules.priceScale)))

    /**
     * Relative price bump when a bout is announced (0.07 = +7 %).
     * [eloDiff] is opponent Elo minus fighter Elo; a stronger opponent adds a step bonus.
     */
    fun announcementBump(type: BoutType, eloDiff: Double, rules: RuleSet = RuleSet.V2): Double {
        val base =
            when (type) {
                BoutType.NORMAL -> rules.annNormal
                BoutType.MAIN -> rules.annMain
                BoutType.TITLE -> rules.annTitle
            }
        val opp =
            rules.annStrongerOpp
                .filter { eloDiff > it.above }
                .maxByOrNull { it.above }
                ?.bump ?: 0.0
        return base + opp
    }

    /** Implied win probability of the fighter from decimal odds (vig removed). */
    fun impliedProbability(oddsFighter: Double, oddsOpponent: Double): Double =
        (1.0 / oddsFighter) / (1.0 / oddsFighter + 1.0 / oddsOpponent)

    /**
     * Fight-week price factor: 1 + oddsDrift * (probability from odds - Elo expectation).
     * Returns 1.0 when odds are missing.
     */
    fun oddsDrift(
        oddsFighter: Double?,
        oddsOpponent: Double?,
        eloFighter: Double,
        eloOpponent: Double,
        rules: RuleSet = RuleSet.V2,
    ): Double {
        if (oddsFighter == null || oddsOpponent == null) return 1.0
        val edge = impliedProbability(oddsFighter, oddsOpponent) - Elo.expected(eloFighter, eloOpponent)
        return 1.0 + rules.oddsDrift * edge
    }

    /** Applies the demand factor, capped to 1 ± demandCap. */
    fun applyDemand(price: Money, factor: Double, rules: RuleSet = RuleSet.V2): Money =
        price * factor.coerceIn(1.0 - rules.demandCap, 1.0 + rules.demandCap)

    /** Market price = base value × event multiplier × demand factor (capped). */
    fun price(
        baseValue: Money,
        eventMultiplier: Double,
        demandFactor: Double = 1.0,
        rules: RuleSet = RuleSet.V2,
    ): Money =
        applyDemand(baseValue * eventMultiplier, demandFactor, rules)
}
