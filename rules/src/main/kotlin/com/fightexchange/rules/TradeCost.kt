package com.fightexchange.rules

data class BuyCost(val price: Money, val fee: Money, val extraFee: Money) {
    val freeBuy: Boolean get() = extraFee == Money.ZERO
    val total: Money get() = price + fee + extraFee
}

data class SellProceeds(val price: Money, val fee: Money) {
    val net: Money get() = price - fee
}

object TradeCost {
    /**
     * Cost of buying one piece. [buysThisWeek] counts buys already made this game week; from the
     * (freeBuysPerWeek + 1)-th buy on, extraBuyFee × price is added. Nothing extra in the first
     * week of the season ([firstWeek]).
     */
    fun buy(price: Money, buysThisWeek: Int, firstWeek: Boolean = false, rules: RuleSet = RuleSet.V2): BuyCost {
        val extra = !firstWeek && buysThisWeek >= rules.freeBuysPerWeek
        return BuyCost(price, price * rules.fee, if (extra) price * rules.extraBuyFee else Money.ZERO)
    }

    fun sell(price: Money, rules: RuleSet = RuleSet.V2): SellProceeds = SellProceeds(price, price * rules.fee)

    /** Opening a short sells at the market price minus fee. */
    fun shortOpen(price: Money, rules: RuleSet = RuleSet.V2): SellProceeds = sell(price, rules)

    /** Closing a short buys back at the market price plus fee; it does not count as a weekly buy. */
    fun shortClose(price: Money, rules: RuleSet = RuleSet.V2): BuyCost = BuyCost(price, price * rules.fee, Money.ZERO)
}
