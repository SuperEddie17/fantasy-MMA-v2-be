package com.fightexchange.trading

import com.fightexchange.common.ApiException
import com.fightexchange.payout.CaptainPickDto
import com.fightexchange.payout.CaptainRequest
import com.fightexchange.payout.CaptainService
import com.fightexchange.payout.DividendDto
import com.fightexchange.payout.DividendReads
import com.fightexchange.ranking.NetWorthPointDto
import com.fightexchange.ranking.NetWorthService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Put
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.authentication.Authentication
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200
private const val DEFAULT_NET_WORTH_DAYS = 90L

/** The signed-in player's portfolios and trades (plan.md, F3.3 and F3.5). Somebody else's portfolio answers 404. */
@Controller("/api/v1")
class PortfolioController(
    private val portfolios: PortfolioService,
    private val seasons: SeasonService,
    private val trading: TradingService,
    private val captains: CaptainService,
    private val dividends: DividendReads,
    private val netWorth: NetWorthService,
    private val clock: Clock,
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

    /** Sets the captain for an event (double dividends); the fighter must be held long and on the card. */
    @Put("/portfolios/{id}/captain/{eventId}")
    fun captain(
        authentication: Authentication,
        @PathVariable id: Long,
        @PathVariable eventId: Long,
        @Body request: CaptainRequest,
    ): CaptainPickDto {
        requireOwner(authentication, id)
        return captains.pick(id, eventId, request.fighterId, clock.instant())
    }

    @Get("/portfolios/{id}/captain")
    fun captainPicks(
        authentication: Authentication,
        @PathVariable id: Long,
    ): List<CaptainPickDto> {
        requireOwner(authentication, id)
        return captains.list(id)
    }

    @Get("/portfolios/{id}/dividends{?after,limit}")
    fun dividends(
        authentication: Authentication,
        @PathVariable id: Long,
        @QueryValue after: Long?,
        @QueryValue limit: Int?,
    ): CursorPage<DividendDto> {
        requireOwner(authentication, id)
        return dividends.list(id, after, clamp(limit))
    }

    /** Daily snapshots from [from] to [to] (`yyyy-MM-dd`, default the last 90 days). */
    @Get("/portfolios/{id}/net-worth{?from,to}")
    fun netWorth(
        authentication: Authentication,
        @PathVariable id: Long,
        @QueryValue from: String?,
        @QueryValue to: String?,
    ): List<NetWorthPointDto> {
        requireOwner(authentication, id)
        val end = date(to, "to") ?: LocalDate.now(clock)
        val start = date(from, "from") ?: end.minusDays(DEFAULT_NET_WORTH_DAYS)
        return netWorth.history(id, start, end)
    }

    private fun date(
        value: String?,
        name: String,
    ): LocalDate? =
        try {
            value?.let { LocalDate.parse(it) }
        } catch (e: DateTimeParseException) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Invalid request",
                "$name must be yyyy-MM-dd",
            )
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
