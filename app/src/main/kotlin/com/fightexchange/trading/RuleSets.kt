package com.fightexchange.trading

import com.fightexchange.rules.RuleSet
import jakarta.inject.Singleton
import java.time.Instant

/**
 * The rules valid at a given time. Only version 2 exists so far (the same values as the `rule_set` row),
 * so every season uses [RuleSet.V2]; this is the single place to read other versions from the table later.
 */
@Singleton
class RuleSets {
    @Suppress("UnusedParameter")
    fun at(instant: Instant): RuleSet = RuleSet.V2
}
