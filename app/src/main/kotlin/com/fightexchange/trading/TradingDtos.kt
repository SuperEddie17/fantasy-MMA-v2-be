package com.fightexchange.trading

import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/** Money fields are whole units (1 M = 1 000 000). */
@Serdeable
data class SeasonDto(
    val id: Long,
    val name: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val ruleSetVersion: Int,
)

@Serdeable
data class CreateSeasonRequest(
    val name: String?,
    val startsAt: Instant?,
    val endsAt: Instant?,
)

@Serdeable
data class CreatePortfolioRequest(
    val seasonId: Long? = null,
)

@Serdeable
data class PositionDto(
    val lotId: Long,
    val fighterId: Long,
    val fighterName: String,
    val side: String,
    val openedAt: Instant,
    val openPrice: Long,
    val currentPrice: Long,
)

/** Net worth = cash + value of longs - value of shorts, all at current market prices. */
@Serdeable
data class PortfolioDto(
    val id: Long,
    val userId: Long,
    val seasonId: Long,
    val cash: Long,
    val longValue: Long,
    val shortValue: Long,
    val netWorth: Long,
    val positions: List<PositionDto>,
    val createdAt: Instant,
)

@Serdeable
data class PortfolioSummaryDto(
    val id: Long,
    val seasonId: Long,
    val cash: Long,
    val createdAt: Instant,
)

@Serdeable
data class LedgerEntryDto(
    val id: Long,
    val at: Instant,
    val kind: String,
    val amount: Long,
    val tradeId: Long?,
    val boutId: Long?,
    val reversesId: Long?,
)

@Serdeable
data class TradeDto(
    val id: Long,
    val fighterId: Long,
    val fighterName: String,
    val kind: String,
    val price: Long,
    val fee: Long,
    val extraFee: Long,
    val freeBuy: Boolean,
    val at: Instant,
)

/** Cursor-paginated list, newest first: pass `nextCursor` as `after`; null on the last page. */
@Serdeable
data class CursorPage<T>(
    val items: List<T>,
    val nextCursor: Long?,
)
