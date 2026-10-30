package com.fightexchange.api

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.QueryValue
import java.time.LocalDate

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

/** Public read-only market data (see plan.md, F2.6). */
@Controller("/api/v1/market")
class MarketController(
    private val queries: MarketQueries,
) {
    @Get("{?org,division,sort,offset,limit}")
    fun market(
        @QueryValue org: String?,
        @QueryValue division: String?,
        @QueryValue sort: String?,
        @QueryValue offset: Int?,
        @QueryValue limit: Int?,
    ): HttpResponse<MarketPage> {
        val order = MarketSort.parse(sort) ?: return HttpResponse.badRequest()
        return HttpResponse.ok(
            queries.market(org, division, order, (offset ?: 0).coerceAtLeast(0), clamp(limit)),
        )
    }

    @Get("/{fighterId}")
    fun detail(
        @PathVariable fighterId: Long,
    ): HttpResponse<MarketDetailDto> = queries.detail(fighterId)?.let { HttpResponse.ok(it) } ?: HttpResponse.notFound()

    @Get("/{fighterId}/history{?from,to}")
    fun history(
        @PathVariable fighterId: Long,
        @QueryValue from: LocalDate?,
        @QueryValue to: LocalDate?,
    ): HttpResponse<List<PriceDayDto>> =
        queries.history(fighterId, from, to)?.let { HttpResponse.ok(it) } ?: HttpResponse.notFound()

    @Get("/{fighterId}/events{?after,limit}")
    fun events(
        @PathVariable fighterId: Long,
        @QueryValue after: Long?,
        @QueryValue limit: Int?,
    ): HttpResponse<Page<PriceEventDto>> =
        queries.events(fighterId, after, clamp(limit))?.let { HttpResponse.ok(it) } ?: HttpResponse.notFound()

    private fun clamp(limit: Int?): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
}
