package com.fightexchange.ingest

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/** Current version of the payload schema; stored in every payload so old events stay readable. */
const val PAYLOAD_SCHEMA_VERSION = 1

/** Event payloads accepted by the ingest. The JSON discriminator `type` equals [IngestEventType.wireName]. */
@Serdeable
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = IngestPayload.FighterUpsert::class, name = "fighter.upsert"),
    JsonSubTypes.Type(value = IngestPayload.EventUpsert::class, name = "event.upsert"),
    JsonSubTypes.Type(value = IngestPayload.BoutAnnounced::class, name = "bout.announced"),
    JsonSubTypes.Type(value = IngestPayload.BoutChanged::class, name = "bout.changed"),
    JsonSubTypes.Type(value = IngestPayload.BoutCancelled::class, name = "bout.cancelled"),
    JsonSubTypes.Type(value = IngestPayload.OddsSnapshot::class, name = "odds.snapshot"),
    JsonSubTypes.Type(value = IngestPayload.BoutResult::class, name = "bout.result"),
    JsonSubTypes.Type(value = IngestPayload.RankingSnapshot::class, name = "ranking.snapshot"),
)
sealed interface IngestPayload {
    val schemaVersion: Int
    val eventType: IngestEventType

    @Serdeable
    data class FighterUpsert(
        val externalId: String,
        val name: String,
        val sex: String,
        val organization: String,
        val division: String? = null,
        val nickname: String? = null,
        val country: String? = null,
        override val schemaVersion: Int = PAYLOAD_SCHEMA_VERSION,
    ) : IngestPayload {
        override val eventType get() = IngestEventType.FIGHTER_UPSERT
    }

    @Serdeable
    data class EventUpsert(
        val externalId: String,
        val organization: String,
        val name: String,
        val startsAt: Instant,
        val weighInAt: Instant? = null,
        override val schemaVersion: Int = PAYLOAD_SCHEMA_VERSION,
    ) : IngestPayload {
        override val eventType get() = IngestEventType.EVENT_UPSERT
    }

    /** A fighter reference as given by a source: external id and/or name; admins may give our own [id]. */
    @Serdeable
    data class FighterRef(
        val externalId: String? = null,
        val name: String? = null,
        val division: String? = null,
        val id: Long? = null,
    )

    @Serdeable
    data class BoutAnnounced(
        val eventExternalId: String,
        val boutExternalId: String,
        val fighterA: FighterRef,
        val fighterB: FighterRef,
        val boutType: String = "NORMAL",
        val announcedAt: Instant? = null,
        val eventSource: String? = null,
        override val schemaVersion: Int = PAYLOAD_SCHEMA_VERSION,
    ) : IngestPayload {
        override val eventType get() = IngestEventType.BOUT_ANNOUNCED
    }

    @Serdeable
    data class BoutChanged(
        val boutExternalId: String,
        val fighterA: FighterRef? = null,
        val fighterB: FighterRef? = null,
        val boutType: String? = null,
        val announcedAt: Instant? = null,
        val boutSource: String? = null,
        override val schemaVersion: Int = PAYLOAD_SCHEMA_VERSION,
    ) : IngestPayload {
        override val eventType get() = IngestEventType.BOUT_CHANGED
    }

    @Serdeable
    data class BoutCancelled(
        val boutExternalId: String,
        val reason: String? = null,
        val withdrawnFighter: FighterRef? = null,
        val boutSource: String? = null,
        override val schemaVersion: Int = PAYLOAD_SCHEMA_VERSION,
    ) : IngestPayload {
        override val eventType get() = IngestEventType.BOUT_CANCELLED
    }

    @Serdeable
    data class OddsSnapshot(
        val boutExternalId: String,
        val takenAt: Instant,
        val oddsA: Double,
        val oddsB: Double,
        val closing: Boolean = false,
        val boutSource: String? = null,
        override val schemaVersion: Int = PAYLOAD_SCHEMA_VERSION,
    ) : IngestPayload {
        override val eventType get() = IngestEventType.ODDS_SNAPSHOT
    }

    @Serdeable
    data class Bonus(
        val fighter: FighterRef,
        val type: String,
    )

    @Serdeable
    data class BoutResult(
        val boutExternalId: String,
        val outcome: String,
        val winner: FighterRef? = null,
        val method: String,
        val round: Int? = null,
        val time: String? = null,
        val titleWon: Boolean = false,
        val bonuses: List<Bonus> = emptyList(),
        val weightMisses: List<FighterRef> = emptyList(),
        val boutSource: String? = null,
        override val schemaVersion: Int = PAYLOAD_SCHEMA_VERSION,
    ) : IngestPayload {
        override val eventType get() = IngestEventType.BOUT_RESULT
    }

    @Serdeable
    data class RankedFighter(
        val fighter: FighterRef,
        val rank: Int,
    )

    @Serdeable
    data class RankingSnapshot(
        val organization: String,
        val division: String,
        val takenAt: Instant,
        val ranks: List<RankedFighter>,
        override val schemaVersion: Int = PAYLOAD_SCHEMA_VERSION,
    ) : IngestPayload {
        override val eventType get() = IngestEventType.RANKING_SNAPSHOT
    }
}

enum class IngestEventType(
    val wireName: String,
) {
    FIGHTER_UPSERT("fighter.upsert"),
    EVENT_UPSERT("event.upsert"),
    BOUT_ANNOUNCED("bout.announced"),
    BOUT_CHANGED("bout.changed"),
    BOUT_CANCELLED("bout.cancelled"),
    ODDS_SNAPSHOT("odds.snapshot"),
    BOUT_RESULT("bout.result"),
    RANKING_SNAPSHOT("ranking.snapshot"),
    ;

    companion object {
        fun fromWireName(name: String): IngestEventType =
            entries.firstOrNull { it.wireName == name }
                ?: throw IllegalArgumentException("Unknown ingest event type: $name")
    }
}

enum class IngestStatus { NEW, APPLIED, NEEDS_REVIEW, REJECTED, DUPLICATE }
