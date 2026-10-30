package com.fightexchange.admin

import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import java.time.Clock
import java.time.ZoneOffset

/** Admin market actions (plan.md, F2.7): manual delisting and a price replay after data corrections. */
@Controller("/api/v1/admin/market")
class AdminMarketController(
    private val market: MarketAdmin,
    private val clock: Clock,
) {
    @Post("/{fighterId}/delist")
    fun delist(
        @PathVariable fighterId: Long,
        @Nullable @Body request: DelistRequest?,
    ): HttpResponse<*> =
        when (market.delist(fighterId, request?.reason)) {
            DelistOutcome.Delisted -> HttpResponse.ok(AdminResultDto("DELISTED", fighterId))
            DelistOutcome.NotListed -> HttpResponse.notFound<Any>()
            DelistOutcome.AlreadyDelisted ->
                HttpResponse
                    .status<ErrorDto>(io.micronaut.http.HttpStatus.CONFLICT)
                    .body(ErrorDto("Fighter $fighterId is already delisted"))
        }

    @Post("/replay")
    fun replay(
        @Body request: ReplayRequest,
    ): HttpResponse<*> {
        val from = request.from.atStartOfDay().toInstant(ZoneOffset.UTC)
        if (from > clock.instant()) return HttpResponse.badRequest(ErrorDto("from must not be in the future"))
        val result =
            market.replay(from)
                ?: return HttpResponse
                    .status<ErrorDto>(io.micronaut.http.HttpStatus.CONFLICT)
                    .body(ErrorDto("A market job or another replay is running, try again later"))
        return HttpResponse.ok(
            ReplayResultDto(
                result.from,
                result.to,
                result.steps,
                result.listings,
                result.priceEvents,
                result.dailyRows,
            ),
        )
    }
}
