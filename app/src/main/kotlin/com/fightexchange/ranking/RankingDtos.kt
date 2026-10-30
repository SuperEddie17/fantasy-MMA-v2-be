package com.fightexchange.ranking

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

@Serdeable
data class NetWorthPointDto(
    val day: LocalDate,
    val cash: Long,
    val longValue: Long,
    val shortValue: Long,
    val netWorth: Long,
)

@Serdeable
data class LeaderboardRowDto(
    val rank: Int,
    val portfolioId: Long,
    val displayName: String,
    val netWorth: Long,
    val change: Long,
)

/** `closed` periods are final (a finished month); `nextOffset` is null on the last page. */
@Serdeable
data class LeaderboardDto(
    val seasonId: Long,
    val period: String,
    val closed: Boolean,
    val items: List<LeaderboardRowDto>,
    val nextOffset: Int?,
)
