package com.fightexchange.rules.sim

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fightexchange.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.LocalDate
import kotlin.math.abs

/**
 * Replays the 2025 UFC season (golden/fights.json) with the Kotlin port of sim.py's loop and strategies
 * and compares net worth per strategy with sim.py's own run (golden/season2025.json): within ±2 %
 * (median over seeds for the random strategy) and the same number of trades.
 */
class Season2025Test {
    private val mapper = jacksonObjectMapper()

    private val fights: List<Fight> =
        mapper.readTree(javaClass.getResource("/golden/fights.json")).get("rows").map {
            Fight(
                date = LocalDate.parse(it[0].asText()),
                r = it[1].asText(),
                b = it[2].asText(),
                ro = it[3].takeUnless(JsonNode::isNull)?.asDouble(),
                bo = it[4].takeUnless(JsonNode::isNull)?.asDouble(),
                win = it[5].takeUnless(JsonNode::isNull)?.asText()?.single(),
                finish = it[6].asBoolean(),
                rnd = it[7].asInt(),
                title = it[8].asBoolean(),
                main = it[9].asBoolean(),
                nc = it[10].asBoolean(),
                bonus = it[11].asBoolean(),
            )
        }

    private val expected = mapper.readTree(javaClass.getResource("/golden/season2025.json")).get("scenarios")

    private val scenarios =
        mapOf(
            "v2_limit2" to (RuleSet.V2.copy(freeBuysPerWeek = 99, extraBuyFee = 0.0) to SimOptions(maxTradesWeek = 2)),
            "v2_free2_extra3" to (RuleSet.V2 to SimOptions()),
        )

    private fun median(xs: List<Double>) = xs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    @TestFactory
    fun season2025() =
        scenarios.map { (key, setup) ->
            DynamicTest.dynamicTest(key) {
                val exp = expected.get(key)
                val (mk, players) = runSeason(fights, Strategies.all, setup.first, setup.second, seeds = 200)

                val eloEnd = exp.get("eloEnd")
                eloEnd.properties().forEach { (name, e) ->
                    assertEquals(e.asDouble(), mk.elo(name), 1e-6, "Elo of $name")
                }

                val py = exp.get("players").groupBy({ it.get("strategy").asText() }, { it })
                val kt = players.groupBy { it.name }
                val report =
                    StringBuilder("\n$key: strategy | net worth py / kt (M) | dividends py / kt | trades py / kt\n")
                val failures = mutableListOf<String>()
                for ((name, _) in Strategies.all) {
                    val pyW = median(py.getValue(name).map { it.get("worth").asDouble() })
                    val ktW = median(kt.getValue(name).map { it.worth().toMillions() })
                    val pyD = py.getValue(name).map { it.get("dividends").asDouble() }.average()
                    val ktD = kt.getValue(name).map { it.div.toMillions() }.average()
                    val pyT = py.getValue(name).map { it.get("trades").asDouble() }.average()
                    val ktT = kt.getValue(name).map { it.trades.toDouble() }.average()
                    report.append(
                        "  $name | %.3f / %.3f | %.3f / %.3f | %.1f / %.1f\n".format(pyW, ktW, pyD, ktD, pyT, ktT),
                    )
                    if (abs(ktW - pyW) > 0.02 * abs(pyW) || pyT != ktT) failures += name
                }
                println(report)
                assertTrue(
                    failures.isEmpty(),
                    "net worth off by more than 2 % or trade count differs: $failures$report",
                )
            }
        }
}
