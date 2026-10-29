package com.fightexchange.rules

object Inactivity {
    /**
     * Weekly price factor for inactivity: 1 - decay after [RuleSet.inactiveWeeks] weeks without a fight,
     * unless a bout is announced. A fighter past [RuleSet.delistWeeks] is delisted instead (factor 1).
     */
    fun weeklyFactor(weeksSinceFight: Double, hasAnnounced: Boolean, rules: RuleSet = RuleSet.V2): Double =
        when {
            hasAnnounced -> 1.0
            weeksSinceFight > rules.delistWeeks -> 1.0
            weeksSinceFight > rules.inactiveWeeks -> 1.0 - rules.decay
            else -> 1.0
        }

    fun shouldDelist(weeksSinceFight: Double, hasAnnounced: Boolean, rules: RuleSet = RuleSet.V2): Boolean =
        !hasAnnounced && weeksSinceFight > rules.delistWeeks
}
