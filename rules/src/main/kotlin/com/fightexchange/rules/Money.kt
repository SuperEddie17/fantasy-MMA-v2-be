package com.fightexchange.rules

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Game currency in whole units (1 M = 1 000 000 units). Never use floating point for money:
 * multiplying by a rate goes through [BigDecimal] and rounds [RoundingMode.HALF_EVEN].
 */
@JvmInline
value class Money(val units: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(Math.addExact(units, other.units))

    operator fun minus(other: Money) = Money(Math.subtractExact(units, other.units))

    operator fun unaryMinus() = Money(Math.negateExact(units))

    operator fun times(n: Int) = Money(Math.multiplyExact(units, n.toLong()))

    /** Multiplies by a rate or factor, rounding half-even to whole units. */
    operator fun times(rate: Double): Money = Money(BigDecimal(units).multiply(BigDecimal(rate)).roundToUnits())

    override fun compareTo(other: Money) = units.compareTo(other.units)

    fun toDouble(): Double = units.toDouble()

    fun toMillions(): Double = units / UNITS_PER_MILLION.toDouble()

    override fun toString() = "$units"

    companion object {
        const val UNITS_PER_MILLION = 1_000_000L
        val ZERO = Money(0)

        fun millions(m: Long) = Money(Math.multiplyExact(m, UNITS_PER_MILLION))

        fun millions(m: Double) = ofUnits(m * UNITS_PER_MILLION)

        /** Rounds a computed (floating point) amount of units half-even. */
        fun ofUnits(units: Double): Money {
            require(units.isFinite()) { "amount must be finite: $units" }
            return Money(BigDecimal(units).roundToUnits())
        }

        private fun BigDecimal.roundToUnits() = setScale(0, RoundingMode.HALF_EVEN).longValueExact()
    }
}

fun maxOf(a: Money, b: Money): Money = if (a >= b) a else b

fun Iterable<Money>.sum(): Money = fold(Money.ZERO) { acc, m -> acc + m }
