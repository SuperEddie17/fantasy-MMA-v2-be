package com.fightexchange.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** Behaviour that sim.py does not cover (so it has no golden data). */
class RulesTest {
    private fun m(x: Long) = Money.millions(x)

    private fun m(x: Double) = Money.millions(x)

    @Test
    fun `money rounds half even`() {
        assertEquals(Money(2), Money(5) * 0.5)
        assertEquals(Money(4), Money(7) * 0.5)
        assertEquals(Money(10_000), m(1L) * 0.01)
        assertEquals(Money(-2), -(Money(5) * 0.5))
        assertThrows<ArithmeticException> { Money(Long.MAX_VALUE) + Money(1) }
    }

    @Test
    fun `base price has a floor`() {
        assertEquals(m(6L), Pricing.basePrice(1500.0))
        assertEquals(RuleSet.V2.priceMin, Pricing.basePrice(0.0))
    }

    @Test
    fun `demand factor is capped`() {
        assertEquals(m(13L), Pricing.applyDemand(m(10L), 1.5))
        assertEquals(m(7L), Pricing.applyDemand(m(10L), 0.1))
        assertEquals(m(11L), Pricing.applyDemand(m(10L), 1.1))
        assertEquals(m(11L), Pricing.price(m(10L), 1.1))
    }

    @Test
    fun `announcement bump steps by opponent strength`() {
        assertEquals(0.02, Pricing.announcementBump(BoutType.NORMAL, -50.0), 1e-12)
        assertEquals(0.03, Pricing.announcementBump(BoutType.NORMAL, 50.0), 1e-12)
        assertEquals(0.07, Pricing.announcementBump(BoutType.MAIN, 150.0), 1e-12)
        assertEquals(0.13, Pricing.announcementBump(BoutType.TITLE, 250.0), 1e-12)
        assertEquals(1.0, Pricing.oddsDrift(null, 2.0, 1500.0, 1500.0))
    }

    @Test
    fun `ranking factor moves by rank change, is capped and treats missing rank as unranked`() {
        assertEquals(1.0, Pricing.rankingFactor(5, 5), 1e-12)
        assertEquals(1.015, Pricing.rankingFactor(8, 5), 1e-12)
        assertEquals(0.99, Pricing.rankingFactor(3, 5), 1e-12)
        assertEquals(1.05, Pricing.rankingFactor(15, 1), 1e-12)
        assertEquals(0.95, Pricing.rankingFactor(1, null), 1e-12)
        assertEquals(1.005, Pricing.rankingFactor(null, 15), 1e-12)
    }

    @Test
    fun `inactivity boundaries`() {
        assertEquals(1.0, Inactivity.weeklyFactor(26.0, false))
        assertEquals(0.99, Inactivity.weeklyFactor(26.1, false))
        assertEquals(1.0, Inactivity.weeklyFactor(40.0, true))
        assertFalse(Inactivity.shouldDelist(78.0, false))
        assertTrue(Inactivity.shouldDelist(78.1, false))
        assertFalse(Inactivity.shouldDelist(100.0, true))
    }

    @Test
    fun `dividend example from the rules - underdog KO in round 1 with bonus, captain`() {
        val d =
            Dividend.forWinner(
                FightOutcome(FightResult.WIN, finish = true, round = 1),
                odds = 4.2,
                price = m(6L),
                isCaptain = true,
                bonuses = DividendBonuses(fightNight = true),
            )
        // 6 M * 0.25 * (1.5 * 3.5) / 1.2 = 6.5625 M; + 0.5 M + 1 M = 8.0625 M; captain x2
        assertEquals(Money(6_562_500), d.core)
        assertEquals(Money(1_500_000), d.bonuses)
        assertEquals(Money(16_125_000), d.total)
    }

    @Test
    fun `weight miss lowers the dividend but never below zero`() {
        val win = FightOutcome(FightResult.WIN, finish = false, round = 3)
        val d = Dividend.forWinner(win, 2.0, m(6L), false, DividendBonuses(weightMiss = true))
        assertEquals(Money(1_500_000), d.total) // 2.5 M - 1 M
        val cheap = Dividend.forWinner(win, 1.2, Money(500_000), false, DividendBonuses(weightMiss = true))
        assertEquals(Money.ZERO, cheap.total)
        assertEquals(
            Money.ZERO,
            Dividend.forWinner(FightOutcome(FightResult.LOSS, true, 1), 2.0, m(6L), true, DividendBonuses()).total,
        )
    }

    @Test
    fun `short settles against odds`() {
        assertEquals(m(3.5), Dividend.forShort(FightResult.LOSS, 1.5, 5.0))
        assertEquals(m(-5.0), Dividend.forShort(FightResult.WIN, 5.0, 1.2))
        assertEquals(Money.ZERO, Dividend.forShort(FightResult.DRAW, 5.0, 1.2))
    }

    @Test
    fun `eligibility and FIFO per lot`() {
        val t = Instant.parse("2026-01-10T12:00:00Z")
        val lots = listOf(Lot(3, t.plusSeconds(60)), Lot(1, t.minusSeconds(60)), Lot(2, t.minusSeconds(60)))
        assertTrue(Eligibility.dividendEligible(t.minusSeconds(1), t))
        assertFalse(Eligibility.dividendEligible(t, t))
        assertEquals(listOf(1L, 2L), Eligibility.eligibleLots(lots, t).map { it.id })
        assertEquals(listOf(1L, 2L), Eligibility.lotsToClose(lots, 2).map { it.id })
        assertThrows<IllegalArgumentException> { Eligibility.lotsToClose(lots, 4) }
    }

    @Test
    fun `trade costs`() {
        val free = TradeCost.buy(m(10L), buysThisWeek = 1)
        assertEquals(m(10.1), free.total)
        assertTrue(free.freeBuy)
        assertEquals(m(10.4), TradeCost.buy(m(10L), buysThisWeek = 2).total)
        assertEquals(m(10.1), TradeCost.buy(m(10L), buysThisWeek = 5, firstWeek = true).total)
        assertEquals(m(9.9), TradeCost.sell(m(10L)).net)
        assertEquals(m(10.1), TradeCost.shortClose(m(10L)).total)
    }

    private fun ufc(id: Long, opp: Long? = null, odds: Double? = null) = RosterFighter(id, true, opp, odds)

    private fun other(id: Long) = RosterFighter(id, false)

    @Test
    fun `roster limits`() {
        val roster = Roster(longs = listOf(ufc(1, opp = 2), other(10), other(11)), shorts = listOf(ufc(5)))
        assertEquals(emptyList<RosterViolation>(), RosterRules.validate(roster, RosterAction.Buy(ufc(3))))
        assertEquals(listOf(RosterViolation.ALREADY_HELD), RosterRules.validate(roster, RosterAction.Buy(ufc(1))))
        assertEquals(
            listOf(RosterViolation.BOTH_OPPONENTS),
            RosterRules.validate(roster, RosterAction.Buy(ufc(2, opp = 1))),
        )
        assertEquals(
            listOf(RosterViolation.LONG_AND_SHORT_SAME_FIGHTER),
            RosterRules.validate(roster, RosterAction.Buy(ufc(5))),
        )
        assertEquals(listOf(RosterViolation.MIN_NON_UFC), RosterRules.validate(roster, RosterAction.Sell(10)))
        assertEquals(listOf(RosterViolation.NOT_HELD), RosterRules.validate(roster, RosterAction.Sell(99)))
        assertEquals(
            listOf(RosterViolation.SHORT_ODDS_TOO_HIGH),
            RosterRules.validate(roster, RosterAction.OpenShort(ufc(7, odds = 4.0))),
        )
        assertEquals(emptyList<RosterViolation>(), RosterRules.validate(roster, RosterAction.CloseShort(5)))
    }

    @Test
    fun `roster size limits`() {
        val full = Roster(longs = (1L..13L).map { ufc(it) } + listOf(other(20), other(21)))
        assertEquals(
            listOf(RosterViolation.ROSTER_FULL, RosterViolation.MIN_NON_UFC),
            RosterRules.validate(full, RosterAction.Buy(ufc(99))),
        )
        val noRoomForUfc = Roster(longs = (1L..13L).map { ufc(it) })
        assertEquals(listOf(RosterViolation.MIN_NON_UFC), RosterRules.validate(noRoomForUfc, RosterAction.Buy(ufc(99))))
        assertEquals(emptyList<RosterViolation>(), RosterRules.validate(noRoomForUfc, RosterAction.Buy(other(99))))
        val shorts = Roster(longs = emptyList(), shorts = listOf(ufc(1), ufc(2), ufc(3)))
        assertEquals(
            listOf(RosterViolation.TOO_MANY_SHORTS),
            RosterRules.validate(shorts, RosterAction.OpenShort(ufc(4))),
        )
        // a roster already below the non-UFC minimum may still sell its last non-UFC fighter
        assertEquals(emptyList<RosterViolation>(), RosterRules.validate(Roster(listOf(other(1))), RosterAction.Sell(1)))
        assertTrue(RosterRules.dividendActive(8))
        assertFalse(RosterRules.dividendActive(7))
    }
}
