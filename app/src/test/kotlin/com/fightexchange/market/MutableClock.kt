package com.fightexchange.market

import com.fightexchange.Clocks
import io.micronaut.context.annotation.Replaces
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test moves by hand (property `test.clock=mutable`), so a season can be driven through the ingest. */
@Singleton
@Replaces(bean = Clock::class, factory = Clocks::class)
@Requires(property = "test.clock", value = "mutable")
class MutableClock : Clock() {
    @Volatile var now: Instant = Instant.parse("2025-01-01T00:00:00Z")

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
