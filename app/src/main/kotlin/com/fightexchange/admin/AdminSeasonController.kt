package com.fightexchange.admin

import com.fightexchange.trading.CreateSeasonRequest
import com.fightexchange.trading.LedgerCheck
import com.fightexchange.trading.LedgerMismatch
import com.fightexchange.trading.SeasonDto
import com.fightexchange.trading.SeasonService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post

/** Seasons are created by the admin (plan.md, F3.3); the ledger check can also be run by hand (F3.7). */
@Controller("/api/v1/admin")
class AdminSeasonController(
    private val seasons: SeasonService,
    private val ledgerCheck: LedgerCheck,
) {
    @Post("/seasons")
    fun create(
        @Body request: CreateSeasonRequest,
    ): HttpResponse<SeasonDto> = HttpResponse.created(seasons.create(request.name, request.startsAt, request.endsAt))

    /** Portfolios whose cached cash differs from their ledger (empty = all fine); 409 while the nightly job runs. */
    @Get("/ledger/check")
    fun check(): HttpResponse<List<LedgerMismatch>> =
        ledgerCheck.run()?.let { HttpResponse.ok(it) } ?: HttpResponse.status(HttpStatus.CONFLICT)
}
