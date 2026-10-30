package com.fightexchange.api

import io.micronaut.serde.annotation.Serdeable
import java.time.Instant
import java.time.LocalDate

@Serdeable
data class OrganizationDto(
    val code: String,
    val name: String,
)

/** Cursor-paginated list: pass `nextCursor` as `after` to get the next page; null on the last page. */
@Serdeable
data class Page<T>(
    val items: List<T>,
    val nextCursor: Long?,
)

@Serdeable
data class FighterDto(
    val id: Long,
    val name: String,
    val nickname: String?,
    val sex: String,
    val division: String?,
    val organization: String,
    val country: String?,
)

@Serdeable
data class FighterBoutDto(
    val boutId: Long,
    val eventId: Long,
    val eventName: String,
    val startsAt: Instant,
    val opponentId: Long,
    val opponentName: String,
    val status: String,
    val result: String?,
    val method: String?,
)

@Serdeable
data class FighterDetailDto(
    val fighter: FighterDto,
    val elo: Double?,
    val recentBouts: List<FighterBoutDto>,
)

@Serdeable
data class EventDto(
    val id: Long,
    val organization: String,
    val name: String,
    val startsAt: Instant,
    val localDate: LocalDate?,
    val weighInAt: Instant?,
    val status: String,
)

@Serdeable
data class BoutResultDto(
    val outcome: String,
    val winnerId: Long?,
    val method: String,
    val round: Int?,
    val time: String?,
    val titleWon: Boolean,
)

@Serdeable
data class BoutDto(
    val id: Long,
    val fighterA: FighterDto,
    val fighterB: FighterDto,
    val boutType: String,
    val status: String,
    val announcedAt: Instant?,
    val result: BoutResultDto?,
)

@Serdeable
data class EventDetailDto(
    val event: EventDto,
    val bouts: List<BoutDto>,
)
