package com.fightexchange

import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.time.Clock

/** The time source of the ingest handlers and market jobs; tests replace it to drive a season through the ingest. */
@Factory
class Clocks {
    @Singleton
    fun clock(): Clock = Clock.systemUTC()
}
