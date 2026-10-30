package com.fightexchange.api

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.QueryValue
import java.time.Instant

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

/** Public read-only sport data (see plan.md, F1.6). */
@Controller("/api/v1")
class SportController(
    private val queries: SportQueries,
) {
    @Get("/organizations")
    fun organizations(): List<OrganizationDto> = queries.organizations()

    @Get("/fighters{?org,division,q,after,limit}")
    fun fighters(
        @QueryValue org: String?,
        @QueryValue division: String?,
        @QueryValue q: String?,
        @QueryValue after: Long?,
        @QueryValue limit: Int?,
    ): Page<FighterDto> = queries.fighters(org, division, q, after, clamp(limit))

    @Get("/fighters/{id}")
    fun fighter(
        @PathVariable id: Long,
    ): HttpResponse<FighterDetailDto> = queries.fighter(id)?.let { HttpResponse.ok(it) } ?: HttpResponse.notFound()

    @Get("/events{?from,to,org,after,limit}")
    fun events(
        @QueryValue from: Instant?,
        @QueryValue to: Instant?,
        @QueryValue org: String?,
        @QueryValue after: Long?,
        @QueryValue limit: Int?,
    ): Page<EventDto> = queries.events(from, to, org, after, clamp(limit))

    @Get("/events/{id}")
    fun event(
        @PathVariable id: Long,
    ): HttpResponse<EventDetailDto> = queries.event(id)?.let { HttpResponse.ok(it) } ?: HttpResponse.notFound()

    private fun clamp(limit: Int?): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
}
