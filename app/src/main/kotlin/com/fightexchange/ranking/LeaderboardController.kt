package com.fightexchange.ranking

import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import java.time.Clock

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

/** Public rankings: overall (`SEASON`) and the monthly sprint (`MONTH-yyyy-MM`). */
@Controller("/api/v1")
class LeaderboardController(
    private val leaderboards: LeaderboardService,
    private val clock: Clock,
) {
    @Get("/leaderboard{?season,period,offset,limit}")
    fun leaderboard(
        @QueryValue season: Long?,
        @QueryValue period: String?,
        @QueryValue offset: Int?,
        @QueryValue limit: Int?,
    ): LeaderboardDto =
        leaderboards.read(
            season,
            period,
            (offset ?: 0).coerceAtLeast(0),
            (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
            clock.instant(),
        )
}
