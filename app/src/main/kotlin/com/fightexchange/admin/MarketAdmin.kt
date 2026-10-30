package com.fightexchange.admin

import com.fightexchange.market.MarketReplay
import com.fightexchange.market.MarketService
import com.fightexchange.market.PriceCause
import com.fightexchange.market.PriceEventKind
import com.fightexchange.market.ReplayResult
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.time.Clock
import java.time.Instant

sealed interface DelistOutcome {
    data object Delisted : DelistOutcome

    data object NotListed : DelistOutcome

    data object AlreadyDelisted : DelistOutcome
}

/** Admin actions on the market that are not ingest events: manual delisting and the replay of prices. */
@Singleton
class MarketAdmin(
    private val tx: TransactionOperations<Connection>,
    private val market: MarketService,
    private val replay: MarketReplay,
    private val clock: Clock,
) {
    fun delist(
        fighterId: Long,
        reason: String?,
    ): DelistOutcome =
        tx.executeWrite { status ->
            val conn = status.connection
            val current = market.listing(conn, fighterId)
            when {
                current == null -> DelistOutcome.NotListed
                current.status == "DELISTED" -> DelistOutcome.AlreadyDelisted
                else -> {
                    val cause = PriceCause(PriceEventKind.DELIST, clock.instant(), note = reason ?: "delisted by admin")
                    check(market.delist(conn, fighterId, cause))
                    DelistOutcome.Delisted
                }
            }
        }

    /** Null when a market job or another replay is running. */
    fun replay(from: Instant): ReplayResult? = replay.replay(from)
}
