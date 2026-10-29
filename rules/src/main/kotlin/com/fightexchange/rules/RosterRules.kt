package com.fightexchange.rules

/**
 * A fighter as seen by roster validation. [announcedOpponentId] and [announcedOdds] describe the
 * fighter's announced bout, if any.
 */
data class RosterFighter(
    val id: Long,
    val ufc: Boolean,
    val announcedOpponentId: Long? = null,
    val announcedOdds: Double? = null,
)

data class Roster(val longs: List<RosterFighter>, val shorts: List<RosterFighter> = emptyList())

sealed interface RosterAction {
    data class Buy(val fighter: RosterFighter) : RosterAction

    data class Sell(val fighterId: Long) : RosterAction

    data class OpenShort(val fighter: RosterFighter) : RosterAction

    data class CloseShort(val fighterId: Long) : RosterAction
}

enum class RosterViolation {
    ROSTER_FULL,
    TOO_MANY_SHORTS,
    ALREADY_HELD,
    NOT_HELD,
    LONG_AND_SHORT_SAME_FIGHTER,
    BOTH_OPPONENTS,
    MIN_NON_UFC,
    SHORT_ODDS_TOO_HIGH,
}

object RosterRules {
    /**
     * Checks an action against the roster limits: at most maxRoster longs and maxShorts shorts, no long and
     * short on the same fighter, not both opponents of an announced bout, room for minNonUfc non-UFC fighters,
     * and shorts only on fighters whose announced odds are at most oddsCap. Empty list = allowed.
     * The roster minimum (minRoster) is not a trading limit, see [dividendActive].
     */
    fun validate(roster: Roster, action: RosterAction, rules: RuleSet = RuleSet.V2): List<RosterViolation> =
        when (action) {
            is RosterAction.Buy -> validateBuy(roster, action.fighter, rules)
            is RosterAction.Sell -> validateSell(roster, action.fighterId, rules)
            is RosterAction.OpenShort -> validateOpenShort(roster, action.fighter, rules)
            is RosterAction.CloseShort ->
                if (roster.shorts.none { it.id == action.fighterId }) listOf(RosterViolation.NOT_HELD) else emptyList()
        }

    private fun validateBuy(roster: Roster, f: RosterFighter, rules: RuleSet) =
        buildList {
            val longIds = roster.longs.map { it.id }.toSet()
            if (f.id in longIds) add(RosterViolation.ALREADY_HELD)
            if (roster.shorts.any { it.id == f.id }) add(RosterViolation.LONG_AND_SHORT_SAME_FIGHTER)
            if (roster.longs.size >= rules.maxRoster) add(RosterViolation.ROSTER_FULL)
            if (f.announcedOpponentId != null && f.announcedOpponentId in longIds) add(RosterViolation.BOTH_OPPONENTS)
            // Keep room for minNonUfc non-UFC fighters.
            if (f.ufc && roster.longs.count { it.ufc } + 1 > rules.maxRoster - rules.minNonUfc) {
                add(RosterViolation.MIN_NON_UFC)
            }
        }

    private fun validateSell(roster: Roster, fighterId: Long, rules: RuleSet): List<RosterViolation> {
        val f = roster.longs.firstOrNull { it.id == fighterId } ?: return listOf(RosterViolation.NOT_HELD)
        if (f.ufc) return emptyList()
        // Only a sale that breaks the limit is refused; a roster already below it may shrink.
        val nonUfc = roster.longs.count { !it.ufc }
        return if (nonUfc == rules.minNonUfc) listOf(RosterViolation.MIN_NON_UFC) else emptyList()
    }

    private fun validateOpenShort(roster: Roster, f: RosterFighter, rules: RuleSet) =
        buildList {
            if (roster.shorts.any { it.id == f.id }) add(RosterViolation.ALREADY_HELD)
            if (roster.longs.any { it.id == f.id }) add(RosterViolation.LONG_AND_SHORT_SAME_FIGHTER)
            if (roster.shorts.size >= rules.maxShorts) add(RosterViolation.TOO_MANY_SHORTS)
            if (f.announcedOdds != null && f.announcedOdds > rules.oddsCap) add(RosterViolation.SHORT_ODDS_TOO_HIGH)
        }

    /** Dividends are paid only to portfolios holding at least minRoster longs at the event. */
    fun dividendActive(longCount: Int, rules: RuleSet = RuleSet.V2): Boolean = longCount >= rules.minRoster
}
