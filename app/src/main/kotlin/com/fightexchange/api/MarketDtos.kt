package com.fightexchange.api

import io.micronaut.serde.annotation.Serdeable
import java.time.Instant
import java.time.LocalDate

/** Prices are `Money` (whole units, 1 M = 1 000 000); `change7d` is a fraction (0.05 = +5 %). */
@Serdeable
data class MarketRowDto(
    val fighter: FighterDto,
    val status: String,
    val price: Long,
    val change7d: Double,
    val lastFightAt: Instant?,
)

@Serdeable
data class MarketDetailDto(
    val fighter: FighterDto,
    val status: String,
    val price: Long,
    val baseValue: Long,
    val eventMultiplier: Double,
    val demandFactor: Double,
    val change7d: Double,
    val elo: Double?,
    val listedAt: Instant,
    val delistedAt: Instant?,
    val lastFightAt: Instant?,
)

@Serdeable
data class PriceDayDto(
    val day: LocalDate,
    val open: Long,
    val close: Long,
    val low: Long,
    val high: Long,
)

@Serdeable
data class PriceEventDto(
    val id: Long,
    val at: Instant,
    val kind: String,
    val factor: Double?,
    val priceBefore: Long,
    val priceAfter: Long,
    val boutId: Long?,
    val note: String?,
)

/** Offset-paginated list (sorted lists have no stable id cursor): pass `nextOffset` as `offset`. */
@Serdeable
data class MarketPage(
    val items: List<MarketRowDto>,
    val nextOffset: Int?,
)
