package com.fightexchange.rules

import java.time.Instant

/** One unit of a position. One lot = one piece, so dividend eligibility and FIFO work per piece. */
data class Lot(val id: Long, val openedAt: Instant)

object Eligibility {
    /** A lot earns the bout's dividend only if it was bought before the bout was announced. */
    fun dividendEligible(boughtAt: Instant, announcedAt: Instant): Boolean = boughtAt < announcedAt

    fun eligibleLots(lots: List<Lot>, announcedAt: Instant): List<Lot> =
        lots.filter { dividendEligible(it.openedAt, announcedAt) }

    /** Lots closed by selling [quantity] pieces: oldest first (FIFO). */
    fun lotsToClose(lots: List<Lot>, quantity: Int): List<Lot> {
        require(quantity in 0..lots.size) { "cannot close $quantity of ${lots.size} lots" }
        return lots.sortedWith(compareBy<Lot> { it.openedAt }.thenBy { it.id }).take(quantity)
    }
}
