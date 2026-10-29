package com.fightexchange.rules

/** Bout result from the point of view of one fighter. */
enum class FightResult { WIN, LOSS, DRAW, NO_CONTEST }

/** How the fighter's bout ended; [round] matters only for a finish. */
data class FightOutcome(val result: FightResult, val finish: Boolean, val round: Int)

data class DividendBonuses(
    val fightNight: Boolean = false,
    val titleWon: Boolean = false,
    val weightMiss: Boolean = false,
)

data class DividendBreakdown(
    val core: Money,
    val bonuses: Money,
    val captain: Boolean,
    val total: Money,
) {
    companion object {
        val NONE = DividendBreakdown(Money.ZERO, Money.ZERO, false, Money.ZERO)
    }
}

object Dividend {
    /**
     * Dividend for holding a winner (rules v2):
     * (price × yieldScale × base × min(odds, oddsCap) / yieldNorm + bonuses) × captain multiplier.
     * Base is baseFin for a finish, baseDec otherwise; missing odds use defaultOdds.
     * Bonuses are flat amounts (first-round finish, bonus of the night, title, weight miss).
     * Loss, draw and no contest pay nothing. The total is never negative.
     */
    fun forWinner(
        outcome: FightOutcome,
        odds: Double?,
        price: Money,
        isCaptain: Boolean,
        bonuses: DividendBonuses,
        rules: RuleSet = RuleSet.V2,
    ): DividendBreakdown {
        if (outcome.result != FightResult.WIN) return DividendBreakdown.NONE
        val base = if (outcome.finish) rules.baseFin else rules.baseDec
        val cappedOdds = minOf(odds ?: rules.defaultOdds, rules.oddsCap)
        val core = price.toDouble() * rules.yieldScale * (base * cappedOdds) / rules.yieldNorm
        var bonus = Money.ZERO
        if (outcome.finish && outcome.round == 1) bonus += rules.bonusR1
        if (bonuses.fightNight) bonus += rules.bonusNight
        if (bonuses.titleWon) bonus += rules.bonusTitle
        if (bonuses.weightMiss) bonus += rules.weightMiss
        val mult = if (isCaptain) rules.captainMult else 1.0
        val total = Money.ofUnits(maxOf(0.0, (core + bonus.toDouble()) * mult))
        return DividendBreakdown(Money.ofUnits(core), bonus, isCaptain, total)
    }

    /**
     * Settlement of a short at the bout (rules v2): if the fighter loses, the short receives
     * shortUnit × min(opponent odds, oddsCap); if the fighter wins, the short pays shortUnit × fighter odds
     * (no cap). Draw and no contest settle at zero. Missing odds use defaultOdds.
     */
    fun forShort(result: FightResult, oddsFighter: Double?, oddsOpponent: Double?, rules: RuleSet = RuleSet.V2): Money =
        when (result) {
            FightResult.LOSS -> rules.shortUnit * minOf(oddsOpponent ?: rules.defaultOdds, rules.oddsCap)
            FightResult.WIN -> -(rules.shortUnit * (oddsFighter ?: rules.defaultOdds))
            FightResult.DRAW, FightResult.NO_CONTEST -> Money.ZERO
        }
}
