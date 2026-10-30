package com.fightexchange.admin

import com.fightexchange.trading.CreateSeasonRequest
import com.fightexchange.trading.SeasonDto
import com.fightexchange.trading.SeasonService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post

/** Seasons are created by the admin (plan.md, F3.3). */
@Controller("/api/v1/admin/seasons")
class AdminSeasonController(
    private val seasons: SeasonService,
) {
    @Post
    fun create(
        @Body request: CreateSeasonRequest,
    ): HttpResponse<SeasonDto> = HttpResponse.created(seasons.create(request.name, request.startsAt, request.endsAt))
}
