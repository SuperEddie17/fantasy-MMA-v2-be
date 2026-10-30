package com.fightexchange.admin

import com.fightexchange.ingest.HistoryImporter
import com.fightexchange.ingest.IngestPayload
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.security.authentication.Authentication
import java.time.Instant

private val OUTCOMES = setOf("WIN", "DRAW", "NO_CONTEST")
private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

/** Admin write API (plan.md, F1.7). Everything goes through the ingest as `source = ADMIN`. */
@Controller("/api/v1/admin")
class AdminController(
    private val ingest: AdminIngest,
    private val lookups: AdminLookups,
    private val merge: FighterMerge,
    private val review: ReviewService,
    private val history: HistoryImporter,
) {
    @Post("/fighters")
    fun createFighter(
        @Body request: CreateFighterRequest,
    ): HttpResponse<*> {
        val key = ingest.newKey()
        val payload =
            IngestPayload.FighterUpsert(
                key,
                request.name,
                request.sex,
                request.organization,
                request.division,
                request.nickname,
                request.country,
            )
        return respond(ingest.run(payload, key)) { lookups.createdFighter(key) }
    }

    @Post("/fighters/{id}/merge")
    fun mergeFighter(
        @PathVariable id: Long,
        @Body request: MergeFighterRequest,
    ): HttpResponse<*> =
        when (val result = merge.merge(id, request.intoId)) {
            MergeResult.Merged -> HttpResponse.ok(AdminResultDto("MERGED", request.intoId))
            is MergeResult.Rejected -> bad(result.reason)
        }

    @Post("/events")
    fun createEvent(
        @Body request: CreateEventRequest,
    ): HttpResponse<*> {
        val key = ingest.newKey()
        val payload =
            IngestPayload.EventUpsert(
                key,
                request.organization,
                request.name,
                request.startsAt,
                request.weighInAt,
            )
        return respond(ingest.run(payload, key)) { lookups.createdEvent(key) }
    }

    @Post("/events/{id}/bouts")
    fun addBout(
        @PathVariable id: Long,
        @Body request: AddBoutRequest,
    ): HttpResponse<*> {
        val event = lookups.event(id) ?: return HttpResponse.notFound<Any>()
        validateFighters(request.fighterA, request.fighterB)?.let { return bad(it) }
        val key = ingest.newKey()
        val payload =
            IngestPayload.BoutAnnounced(
                eventExternalId = event.externalId,
                eventSource = event.source,
                boutExternalId = key,
                fighterA = byId(request.fighterA),
                fighterB = byId(request.fighterB),
                boutType = request.boutType,
                announcedAt = request.announcedAt,
            )
        return respond(ingest.run(payload, key)) { lookups.createdBout(key) }
    }

    @Patch("/bouts/{id}")
    fun patchBout(
        @PathVariable id: Long,
        @Body request: PatchBoutRequest,
    ): HttpResponse<*> {
        val bout = lookups.bout(id) ?: return HttpResponse.notFound<Any>()
        validateFighters(request.fighterA, request.fighterB)?.let { return bad(it) }
        val payload =
            IngestPayload.BoutChanged(
                boutExternalId = bout.externalId,
                boutSource = bout.source,
                fighterA = request.fighterA?.let { byId(it) },
                fighterB = request.fighterB?.let { byId(it) },
                boutType = request.boutType,
                announcedAt = request.announcedAt,
            )
        return respond(ingest.run(payload), created = false) { id }
    }

    @Post("/bouts/{id}/cancel")
    fun cancelBout(
        @PathVariable id: Long,
        @Body request: CancelBoutRequest,
    ): HttpResponse<*> {
        val bout = lookups.bout(id) ?: return HttpResponse.notFound<Any>()
        request.withdrawnFighter?.let { if (!lookups.fighterExists(it)) return bad("Fighter $it does not exist") }
        val payload =
            IngestPayload.BoutCancelled(
                boutExternalId = bout.externalId,
                boutSource = bout.source,
                reason = request.reason,
                withdrawnFighter = request.withdrawnFighter?.let { byId(it) },
            )
        return respond(ingest.run(payload), created = false) { id }
    }

    @Post("/bouts/{id}/result")
    fun setResult(
        @PathVariable id: Long,
        @Body request: SetResultRequest,
    ): HttpResponse<*> {
        val bout = lookups.bout(id) ?: return HttpResponse.notFound<Any>()
        validateResult(request)?.let { return bad(it) }
        return respond(ingest.run(resultPayload(bout, request)), created = false) { id }
    }

    @Post("/bouts/{id}/odds")
    fun addOdds(
        @PathVariable id: Long,
        @Body request: AddOddsRequest,
    ): HttpResponse<*> {
        val bout = lookups.bout(id) ?: return HttpResponse.notFound<Any>()
        if (request.oddsA <= 1.0 || request.oddsB <= 1.0) return bad("Decimal odds must be greater than 1")
        val payload =
            IngestPayload.OddsSnapshot(
                boutExternalId = bout.externalId,
                takenAt = request.takenAt ?: Instant.now(),
                oddsA = request.oddsA,
                oddsB = request.oddsB,
                closing = request.closing,
                boutSource = bout.source,
            )
        return respond(ingest.run(payload)) { id }
    }

    /** Whole card at once: everything is validated first, then the results are entered one by one. */
    @Post("/events/{id}/results")
    fun setCardResults(
        @PathVariable id: Long,
        @Body request: CardResultsRequest,
    ): HttpResponse<*> {
        lookups.event(id) ?: return HttpResponse.notFound<Any>()
        val bouts = mutableMapOf<Long, SourceRef>()
        validateCard(id, request, bouts)?.let { return bad(it) }
        val items =
            request.results.map { item ->
                val outcome = ingest.run(resultPayload(bouts.getValue(item.bout), item.result))
                CardResultItemDto(item.bout, outcome.status, outcome.ingestEventId, outcome.reviewItemId)
            }
        return HttpResponse.ok(CardResultsDto(items))
    }

    @Get("/review{?status,after,limit}")
    fun reviewQueue(
        @QueryValue status: String?,
        @QueryValue after: Long?,
        @QueryValue limit: Int?,
    ): List<ReviewItemDto> =
        review.list(
            (status ?: "OPEN").uppercase(),
            after,
            (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
        )

    @Post("/review/{id}/resolve")
    fun resolveReview(
        @PathVariable id: Long,
        @Body request: ResolveReviewRequest,
        authentication: Authentication,
    ): HttpResponse<*> =
        when (val result = review.resolve(id, request, authentication.name)) {
            ResolveResult.Done -> HttpResponse.ok(AdminResultDto("RESOLVED", id))
            ResolveResult.NotFound -> HttpResponse.notFound<Any>()
            is ResolveResult.Invalid -> bad(result.message)
            is ResolveResult.Conflict ->
                HttpResponse
                    .status<ErrorDto>(
                        io.micronaut.http.HttpStatus.CONFLICT,
                    ).body(ErrorDto(result.message))
        }

    /** Fills [bouts] with the card's bouts by id; returns the first problem found, if any. */
    private fun validateCard(
        eventId: Long,
        request: CardResultsRequest,
        bouts: MutableMap<Long, SourceRef>,
    ): String? {
        val ids = request.results.map { it.bout }
        if (ids.isEmpty() || ids.toSet().size != ids.size) return "results must list each bout once and not be empty"
        for (item in request.results) {
            val bout = lookups.boutOfEvent(item.bout, eventId) ?: return "Bout ${item.bout} is not on event $eventId"
            validateResult(item.result)?.let { return "Bout ${item.bout}: $it" }
            bouts[item.bout] = bout
        }
        return null
    }

    private fun resultPayload(
        bout: SourceRef,
        request: SetResultRequest,
    ): IngestPayload.BoutResult =
        IngestPayload.BoutResult(
            boutExternalId = bout.externalId,
            boutSource = bout.source,
            outcome = request.outcome,
            winner = request.winner?.let { byId(it) },
            method = request.method,
            round = request.round,
            time = request.time,
            titleWon = request.titleWon,
            bonuses = request.bonuses.map { IngestPayload.Bonus(byId(it.fighter), it.type) },
            weightMisses = request.weightMisses.map { byId(it) },
        )

    @Post("/import/history", consumes = [MediaType.MULTIPART_FORM_DATA])
    fun importHistory(
        file: CompletedFileUpload,
        @QueryValue(defaultValue = "UFC") organization: String,
    ): HttpResponse<*> =
        try {
            HttpResponse.ok(history.import(file.inputStream.reader(), organization))
        } catch (e: IllegalArgumentException) {
            bad(e.message ?: "Invalid history CSV")
        }

    private fun byId(id: Long) = IngestPayload.FighterRef(id = id)

    private fun bad(message: String): HttpResponse<*> = HttpResponse.badRequest(ErrorDto(message))

    private fun validateFighters(
        a: Long?,
        b: Long?,
    ): String? =
        listOfNotNull(a, b).firstOrNull { !lookups.fighterExists(it) }?.let { "Fighter $it does not exist" }
            ?: if (a != null && a == b) "A bout needs two different fighters" else null

    private fun validateResult(r: SetResultRequest): String? =
        when {
            r.outcome !in OUTCOMES -> "outcome must be one of $OUTCOMES"
            r.outcome == "WIN" && r.winner == null -> "A WIN needs a winner"
            r.outcome != "WIN" && r.winner != null -> "Only a WIN has a winner"
            else ->
                (listOfNotNull(r.winner) + r.bonuses.map { it.fighter } + r.weightMisses)
                    .firstOrNull { !lookups.fighterExists(it) }
                    ?.let { "Fighter $it does not exist" }
        }

    /** 201 with the created id, 202 when the change waits in the review queue, 422 when it was rejected. */
    private fun respond(
        outcome: AdminOutcome,
        created: Boolean = true,
        id: () -> Long?,
    ): HttpResponse<*> =
        when (outcome.status) {
            "APPLIED" -> {
                val body = AdminResultDto("APPLIED", id(), outcome.ingestEventId)
                if (created) HttpResponse.created(body) else HttpResponse.ok(body)
            }
            "NEEDS_REVIEW" ->
                HttpResponse.accepted<AdminResultDto>().body(
                    AdminResultDto("NEEDS_REVIEW", null, outcome.ingestEventId, outcome.reviewItemId),
                )
            else ->
                HttpResponse
                    .unprocessableEntity<ErrorDto>()
                    .body(ErrorDto("Change was rejected (ingest event ${outcome.ingestEventId})"))
        }
}
