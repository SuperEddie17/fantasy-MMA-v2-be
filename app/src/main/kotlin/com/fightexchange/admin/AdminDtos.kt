package com.fightexchange.admin

import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

@Serdeable
data class CreateFighterRequest(
    val name: String,
    val sex: String,
    val organization: String,
    val division: String? = null,
    val nickname: String? = null,
    val country: String? = null,
)

@Serdeable
data class MergeFighterRequest(
    val intoId: Long,
)

@Serdeable
data class CreateEventRequest(
    val organization: String,
    val name: String,
    val startsAt: Instant,
    val weighInAt: Instant? = null,
)

@Serdeable
data class AddBoutRequest(
    val fighterA: Long,
    val fighterB: Long,
    val boutType: String = "NORMAL",
    val announcedAt: Instant? = null,
)

@Serdeable
data class PatchBoutRequest(
    val fighterA: Long? = null,
    val fighterB: Long? = null,
    val boutType: String? = null,
    val announcedAt: Instant? = null,
)

@Serdeable
data class CancelBoutRequest(
    val reason: String? = null,
    val withdrawnFighter: Long? = null,
)

@Serdeable
data class BonusRequest(
    val fighter: Long,
    val type: String,
)

@Serdeable
data class SetResultRequest(
    val outcome: String,
    val winner: Long? = null,
    val method: String,
    val round: Int? = null,
    val time: String? = null,
    val titleWon: Boolean = false,
    val bonuses: List<BonusRequest> = emptyList(),
    val weightMisses: List<Long> = emptyList(),
)

/** `action` is CONFIRM, REJECT or ASSIGN (ASSIGN links [externalId] of the event's source to [fighterId]). */
@Serdeable
data class ResolveReviewRequest(
    val action: String,
    val fighterId: Long? = null,
    val externalId: String? = null,
)

/** Result of an admin write: [id] of the created/changed row, or the review item that holds the change. */
@Serdeable
data class AdminResultDto(
    val status: String,
    val id: Long? = null,
    val ingestEventId: Long? = null,
    val reviewItemId: Long? = null,
)

@Serdeable
data class ErrorDto(
    val error: String,
)

@Serdeable
data class ReviewItemDto(
    val id: Long,
    val reason: String,
    val status: String,
    val createdAt: Instant,
    val resolvedBy: String?,
    val resolvedAt: Instant?,
    val ingestEventId: Long,
    val source: String,
    val eventType: String,
    val payload: String,
)
