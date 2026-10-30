package com.fightexchange.trading

import com.fightexchange.common.ApiException
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.authentication.Authentication

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

/** The signed-in player's portfolios and trades (plan.md, F3.3 and F3.5). Somebody else's portfolio answers 404. */
@Controller("/api/v1")
class PortfolioController(
    private val portfolios: PortfolioService,
    private val seasons: SeasonService,
    private val trading: TradingService,
) {
    @Get("/seasons")
    fun seasons(): List<SeasonDto> = seasons.list()

    @Get("/portfolios")
    fun mine(authentication: Authentication): List<PortfolioSummaryDto> = portfolios.ownedBy(userId(authentication))

    @Post("/portfolios")
    fun join(
        authentication: Authentication,
        @Body request: CreatePortfolioRequest?,
    ): HttpResponse<PortfolioDto> =
        HttpResponse.created(portfolios.create(userId(authentication), request?.seasonId))

    @Get("/portfolios/{id}")
    fun detail(
        authentication: Authentication,
        @PathVariable id: Long,
    ): HttpResponse<PortfolioDto> {
        requireOwner(authentication, id)
        return portfolios.view(id)?.let { HttpResponse.ok(it) } ?: HttpResponse.notFound()
    }

    @Get("/portfolios/{id}/ledger{?after,limit}")
    fun ledger(
        authentication: Authentication,
        @PathVariable id: Long,
        @QueryValue after: Long?,
        @QueryValue limit: Int?,
    ): CursorPage<LedgerEntryDto> {
        requireOwner(authentication, id)
        return portfolios.ledger(id, after, clamp(limit))
    }

    @Get("/portfolios/{id}/trades{?after,limit}")
    fun trades(
        authentication: Authentication,
        @PathVariable id: Long,
        @QueryValue after: Long?,
        @QueryValue limit: Int?,
    ): CursorPage<TradeDto> {
        requireOwner(authentication, id)
        return portfolios.trades(id, after, clamp(limit))
    }

    @Post("/portfolios/{id}/quote")
    fun quote(
        authentication: Authentication,
        @PathVariable id: Long,
        @Body request: TradeRequest,
    ): QuoteDto {
        requireOwner(authentication, id)
        return trading.quote(id, request)
    }

    /** 201 for a new trade, 200 when the idempotency key had been used and the stored trade is returned. */
    @Post("/portfolios/{id}/trades")
    fun trade(
        authentication: Authentication,
        @PathVariable id: Long,
        @Body request: TradeRequest,
    ): HttpResponse<TradeResultDto> {
        requireOwner(authentication, id)
        val result = trading.trade(id, request)
        return if (result.replayed) HttpResponse.ok(result) else HttpResponse.created(result)
    }

    private fun requireOwner(
        authentication: Authentication,
        portfolioId: Long,
    ) {
        if (portfolios.ownerOf(portfolioId) != userId(authentication)) {
            throw ApiException(HttpStatus.NOT_FOUND, "PORTFOLIO_NOT_FOUND", "Portfolio does not exist")
        }
    }

    private fun clamp(limit: Int?) = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
}

/** The player's id (the JWT subject). The static admin account has none and owns no portfolio. */
internal fun userId(authentication: Authentication): Long =
    authentication.name.toLongOrNull()
        ?: throw ApiException(HttpStatus.FORBIDDEN, "PLAYER_ONLY", "Only player accounts have portfolios")
