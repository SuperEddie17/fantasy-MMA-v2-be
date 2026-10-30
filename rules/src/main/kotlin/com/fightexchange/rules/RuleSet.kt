package com.fightexchange.rules

/**
 * Every number from the game rules. Values are the rules v2 defaults; at runtime the app loads
 * the version valid at the time of the action from the `rule_set` table.
 */
data class RuleSet(
    val budget: Money = Money.millions(100),
    // Elo
    val eloStart: Double = 1500.0,
    val eloK: Double = 40.0,
    val eloFinishMult: Double = 1.25,
    // Price = priceAt1500 * e^((elo - 1500) / priceScale), at least priceMin
    val priceAt1500: Money = Money.millions(6),
    val priceScale: Double = 200.0,
    val priceMin: Money = Money(500_000),
    // Announcement bumps
    val annNormal: Double = 0.02,
    val annMain: Double = 0.05,
    val annTitle: Double = 0.10,
    /** Extra bump when the opponent is stronger by more than [EloStep.above] Elo points; highest match wins. */
    val annStrongerOpp: List<EloStep> = listOf(EloStep(0.0, 0.01), EloStep(100.0, 0.02), EloStep(200.0, 0.03)),
    val cancelDropMin: Double = 0.05,
    val cancelDropMax: Double = 0.10,
    val oddsDrift: Double = 0.05,
    /** Price change per rank gained or lost between two ranking snapshots (0.005 = 0.5 %). */
    val rankingStep: Double = 0.005,
    /** One snapshot moves a price by at most this much. */
    val rankingCap: Double = 0.05,
    /** Rank assumed for a fighter missing from a ranking (entering or leaving the top). */
    val rankingUnranked: Int = 16,
    // Inactivity
    val inactiveWeeks: Double = 26.0,
    val decay: Double = 0.01,
    val delistWeeks: Double = 78.0,
    val demandCap: Double = 0.30,
    // Dividends
    val baseDec: Double = 1.0,
    val baseFin: Double = 1.5,
    val oddsCap: Double = 3.5,
    /** Odds used when a bout has no odds at all. */
    val defaultOdds: Double = 2.0,
    val yieldScale: Double = 0.25,
    /** Divisor of the yield formula: price * yieldScale * (base * odds) / yieldNorm. */
    val yieldNorm: Double = 1.2,
    val bonusR1: Money = Money(500_000),
    val bonusNight: Money = Money.millions(1),
    val bonusTitle: Money = Money.millions(3),
    val weightMiss: Money = Money.millions(-1),
    val captainMult: Double = 2.0,
    /** Short payoff unit: the short pays or receives this amount times the odds. */
    val shortUnit: Money = Money.millions(1),
    // Trading
    val fee: Double = 0.01,
    val freeBuysPerWeek: Int = 2,
    val extraBuyFee: Double = 0.03,
    // Roster
    val minRoster: Int = 8,
    val maxRoster: Int = 15,
    val maxShorts: Int = 3,
    val minNonUfc: Int = 2,
) {
    companion object {
        val V2 = RuleSet()
    }
}

data class EloStep(val above: Double, val bump: Double)
