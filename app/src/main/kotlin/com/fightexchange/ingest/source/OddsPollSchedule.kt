package com.fightexchange.ingest.source

import java.time.Duration
import java.time.Instant

/**
 * How often to poll an odds source, by how close the nearest event is (plan.md F5.5): every hour from a day
 * before the event, every 3 hours in the week before, otherwise daily. No upcoming event means no polling.
 */
object OddsPollSchedule {
    fun interval(
        now: Instant,
        nextEventStart: Instant?,
    ): Duration? {
        nextEventStart ?: return null
        val untilStart = Duration.between(now, nextEventStart)
        return when {
            untilStart <= Duration.ofDays(1) -> Duration.ofHours(1)
            untilStart <= Duration.ofDays(7) -> Duration.ofHours(3)
            else -> Duration.ofDays(1)
        }
    }

    /** True when the last success is older than the interval (less a minute, so an hourly tick is not skipped). */
    fun due(
        now: Instant,
        lastSuccess: Instant?,
        nextEventStart: Instant?,
    ): Boolean {
        val interval = interval(now, nextEventStart) ?: return false
        return lastSuccess == null || Duration.between(lastSuccess, now) >= interval.minusMinutes(1)
    }
}
