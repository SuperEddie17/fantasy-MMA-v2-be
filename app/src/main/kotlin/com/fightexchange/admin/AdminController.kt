package com.fightexchange.admin

import com.fightexchange.ingest.IngestPayload
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.authentication.Authentication

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
        val fighters = listOfNotNull(request.winner) + request.bonuses.map { it.fighter } + request.weightMisses
        fighters.firstOrNull { !lookups.fighterExists(it) }?.let { return bad("Fighter $it does not exist") }
        val payload =
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
        return respond(ingest.run(payload), created = false) { id }
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
            else -> null
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
