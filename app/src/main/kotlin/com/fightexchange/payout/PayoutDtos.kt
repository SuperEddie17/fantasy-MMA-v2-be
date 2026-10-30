package com.fightexchange.payout

import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/** A portfolio's captain for an event. Captains earn double dividends (`captainMult`). */
@Serdeable
data class CaptainPickDto(
    val eventId: Long,
    val fighterId: Long,
    val fighterName: String,
    val pickedAt: Instant,
    val locked: Boolean,
)

@Serdeable
data class CaptainRequest(
    val fighterId: Long?,
)

/**
 * One payout with its calculation: for a long, `amount = (base + bonuses) x captain multiplier` where
 * `base = price x yieldScale x (baseDec or baseFin x min(odds, oddsCap)) / yieldNorm`; for a short, `amount` is
 * the settlement (positive = the player receives, negative = pays). A reversed row was cancelled by a corrected result.
 */
@Serdeable
data class DividendDto(
    val id: Long,
    val boutId: Long,
    val fighterId: Long,
    val fighterName: String,
    val kind: String,
    val price: Long,
    val base: Long,
    val oddsUsed: Double?,
    val bonuses: Long,
    val captain: Boolean,
    val amount: Long,
    val resultVersion: Int,
    val reversed: Boolean,
    val paidAt: Instant,
)
