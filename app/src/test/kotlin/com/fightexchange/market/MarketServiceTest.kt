package com.fightexchange.market

import com.fightexchange.PostgresTest
import com.fightexchange.ingest.ADMIN_SOURCE
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
import com.fightexchange.rules.Money
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant

@MicronautTest(transactional = false)
class MarketServiceTest : PostgresTest() {
    @Inject lateinit var service: IngestService

    @Inject lateinit var processor: IngestProcessor

    @Inject lateinit var market: MarketService

    @Inject lateinit var tx: TransactionOperations<Connection>

    private var seq = 0
    private val at = Instant.parse("2026-12-01T12:00:00Z")

    @BeforeEach
    @AfterEach
    fun clean() {
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "DELETE FROM price_event; DELETE FROM price_daily; DELETE FROM market_listing; " +
                        "DELETE FROM elo_rating; DELETE FROM fighter_external_id; DELETE FROM fighter; " +
                        "DELETE FROM review_item; DELETE FROM ingest_event",
                )
            }
        }
    }

    private fun upsert(
        id: String,
        name: String,
    ): Long {
        service.submit(ADMIN_SOURCE, "k${seq++}", IngestPayload.FighterUpsert(id, name, "M", "UFC", "Lightweight"))
        processor.processPending()
        return tx.executeRead { st ->
            st.connection.createStatement().use { s ->
                s.executeQuery("SELECT fighter_id FROM fighter_external_id WHERE external_id = '$id'").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }

    private fun <T> read(block: (Connection) -> T): T = tx.executeRead { block(it.connection) }

    private fun <T> write(block: (Connection) -> T): T = tx.executeWrite { block(it.connection) }

    private fun scalar(sql: String): String? =
        read { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    @Test
    fun `new fighter is listed at the Elo 1500 price and logged`() {
        val id = upsert("m1", "Debut Fighter")
        val listing = read { market.listing(it, id) }!!
        assertEquals("IPO", listing.status)
        assertEquals(Money.millions(6), listing.price)
        assertEquals(Money.millions(6), listing.baseValue)
        assertEquals(
            "1",
            scalar("SELECT count(*) FROM price_event WHERE fighter_id = $id AND kind = 'ELO' AND note = 'IPO'"),
        )
    }

    @Test
    fun `repeated upsert does not list twice`() {
        val id = upsert("m2", "Same Fighter")
        service.submit(
            ADMIN_SOURCE,
            "again",
            IngestPayload.FighterUpsert("m2", "Same Fighter", "M", "UFC", "Lightweight"),
        )
        processor.processPending()
        assertEquals("1", scalar("SELECT count(*) FROM price_event WHERE fighter_id = $id"))
        assertEquals("1", scalar("SELECT count(*) FROM market_listing WHERE fighter_id = $id"))
    }

    @Test
    fun `price follows base value times event multiplier and every change is logged`() {
        val id = upsert("m3", "Priced Fighter")
        val cause = PriceCause(PriceEventKind.ANNOUNCED, at, note = "main event")
        val price = write { market.apply(it, id, cause, multiplierFactor = 1.05) }
        assertEquals(Money.millions(6.3), price)
        val reset =
            write {
                market.apply(
                    it,
                    id,
                    PriceCause(PriceEventKind.RESULT_RESET, at),
                    baseValue = Money.millions(7),
                    resetMultiplier = 1.0,
                )
            }
        assertEquals(Money.millions(7), reset)
        assertEquals("ACTIVE", read { market.listing(it, id) }!!.status)
        assertEquals(
            "ANNOUNCED:6000000:6300000,RESULT_RESET:6300000:7000000",
            scalar(
                "SELECT string_agg(kind || ':' || price_before || ':' || price_after, ',' ORDER BY id) " +
                    "FROM price_event WHERE fighter_id = $id AND kind <> 'ELO'",
            ),
        )
    }

    @Test
    fun `debutant with Elo history is priced from the latest rating`() {
        val id = upsert("m4", "Rated Fighter")
        write { c ->
            c.createStatement().use { it.execute("DELETE FROM price_event; DELETE FROM market_listing") }
        }
        assertEquals(1500.0, read { market.currentElo(it, id) })
        assertNull(read { market.listing(it, id) })
        assertNotNull(write { market.ensureListed(it, id, PriceCause(PriceEventKind.ELO, at)) })
        assertEquals(Money.millions(6), read { market.listing(it, id) }!!.price)
    }

    @Test
    fun `delisted fighter keeps their price`() {
        val id = upsert("m5", "Gone Fighter")
        write { c ->
            c.createStatement().use {
                it.execute("UPDATE market_listing SET status = 'DELISTED', delisted_at = now() WHERE fighter_id = $id")
            }
        }
        assertNull(write { market.apply(it, id, PriceCause(PriceEventKind.ANNOUNCED, at), multiplierFactor = 1.1) })
    }
}
