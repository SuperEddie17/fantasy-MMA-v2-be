package com.fightexchange.rules

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Golden tests: every case in golden/functions.json was produced by sim/sim.py
 * (see sim/export_golden.py). Money must match within 1 unit, ratings and factors within 1e-9.
 */
class GoldenTest {
    private val cases: JsonNode =
        jacksonObjectMapper()
            .readTree(GoldenTest::class.java.getResource("/golden/functions.json"))
            .get("cases")

    private fun golden(name: String, check: (JsonNode) -> Unit): DynamicTest =
        DynamicTest.dynamicTest(name) {
            val list = cases.get(name)
            assertTrue(list.size() >= 500, "$name has only ${list.size()} cases")
            list.forEachIndexed { i, c ->
                try {
                    check(c)
                } catch (e: AssertionError) {
                    throw AssertionError("$name case $i: $c", e)
                }
            }
        }

    private fun assertMoney(expected: Long, actual: Money) {
        assertTrue(abs(expected - actual.units) <= 1, "expected $expected units, got ${actual.units}")
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertEquals(expected, actual, 1e-9)
    }

    private fun JsonNode.d(name: String) = get(name).asDouble()

    private fun JsonNode.dOrNull(name: String) = get(name)?.takeUnless { it.isNull }?.asDouble()

    private fun JsonNode.b(name: String) = get(name).asBoolean()

    private fun JsonNode.money(name: String) = Money(get(name).asLong())

    @TestFactory
    fun golden() =
        listOf(
            golden("elo_expected") { assertClose(it.d("out"), Elo.expected(it.d("a"), it.d("b"))) },
            golden("elo_update") {
                val r =
                    Elo.update(
                        it.d("a"),
                        it.d("b"),
                        BoutOutcome.valueOf(it.get("outcome").asText()),
                        it.b("finish"),
                    )
                assertClose(it.d("outA"), r.a)
                assertClose(it.d("outB"), r.b)
            },
            golden("base_price") { assertMoney(it.get("out").asLong(), Pricing.basePrice(it.d("elo"))) },
            golden("announcement_bump") {
                val type = BoutType.valueOf(it.get("type").asText())
                assertClose(it.d("out"), Pricing.announcementBump(type, it.d("eloOpponent") - it.d("eloFighter")))
            },
            golden("odds_drift") {
                val f =
                    Pricing.oddsDrift(
                        it.d("oddsFighter"),
                        it.d("oddsOpponent"),
                        it.d("eloFighter"),
                        it.d("eloOpponent"),
                    )
                assertClose(it.d("out"), f)
            },
            golden("inactivity") {
                val weeks = it.d("weeksSinceFight")
                val announced = it.b("hasAnnounced")
                assertClose(it.d("factor"), Inactivity.weeklyFactor(weeks, announced))
                assertEquals(it.b("delisted"), Inactivity.shouldDelist(weeks, announced))
            },
            golden("dividend_winner") {
                val outcome =
                    FightOutcome(
                        FightResult.valueOf(it.get("result").asText()),
                        it.b("finish"),
                        it.get("round").asInt(),
                    )
                val bonuses = DividendBonuses(fightNight = it.b("fightNight"), titleWon = it.b("title"))
                val d = Dividend.forWinner(outcome, it.dOrNull("odds"), it.money("price"), it.b("captain"), bonuses)
                assertMoney(it.get("out").asLong(), d.total)
            },
            golden("dividend_short") {
                val r = FightResult.valueOf(it.get("result").asText())
                assertMoney(it.get("out").asLong(), Dividend.forShort(r, it.d("oddsFighter"), it.d("oddsOpponent")))
            },
            golden("trade_buy") {
                val c = TradeCost.buy(it.money("price"), it.get("buysThisWeek").asInt(), it.b("firstWeek"))
                assertMoney(it.get("fee").asLong(), c.fee)
                assertMoney(it.get("extraFee").asLong(), c.extraFee)
                assertMoney(it.get("total").asLong(), c.total)
            },
            golden("trade_sell") {
                val s = TradeCost.sell(it.money("price"))
                assertMoney(it.get("fee").asLong(), s.fee)
                assertMoney(it.get("net").asLong(), s.net)
            },
            golden("eligibility") {
                // sim.py counts whole days and pays when held >= 42 days before the fight, i.e. the bout is
                // announced 6 weeks before it. A purchase on the announcement day counts as before it:
                // bought at the start of the day, announced at its end.
                val bought = LocalDate.parse(it.get("boughtOn").asText()).atStartOfDay().toInstant(ZoneOffset.UTC)
                val announced =
                    LocalDate
                        .parse(it.get("fightOn").asText())
                        .minusWeeks(6)
                        .atTime(23, 59, 59)
                        .toInstant(ZoneOffset.UTC)
                assertEquals(it.b("out"), Eligibility.dividendEligible(bought, announced))
            },
        )
}
