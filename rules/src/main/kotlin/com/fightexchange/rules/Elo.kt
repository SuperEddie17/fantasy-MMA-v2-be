package com.fightexchange.rules

import kotlin.math.pow

enum class BoutOutcome { A_WIN, B_WIN, DRAW, NO_CONTEST }

data class EloPair(val a: Double, val b: Double)

object Elo {
    /** Expected score of [a] against [b]. */
    fun expected(a: Double, b: Double): Double = 1.0 / (1.0 + 10.0.pow((b - a) / 400.0))

    /** New ratings after a bout. A finish (KO/TKO, submission) weighs [RuleSet.eloFinishMult] more. */
    fun update(a: Double, b: Double, outcome: BoutOutcome, finish: Boolean, rules: RuleSet = RuleSet.V2): EloPair {
        val score =
            when (outcome) {
                BoutOutcome.A_WIN -> 1.0
                BoutOutcome.B_WIN -> 0.0
                BoutOutcome.DRAW -> 0.5
                BoutOutcome.NO_CONTEST -> return EloPair(a, b)
            }
        val k = rules.eloK * (if (finish) rules.eloFinishMult else 1.0)
        val delta = k * (score - expected(a, b))
        return EloPair(a + delta, b - delta)
    }
}
