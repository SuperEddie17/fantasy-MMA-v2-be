package com.fightexchange.ingest

import com.fightexchange.PostgresTest
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection

@MicronautTest(transactional = false)
class FighterMatcherTest : PostgresTest() {
    @Inject lateinit var matcher: FighterMatcher

    @Inject lateinit var tx: TransactionOperations<Connection>

    private var ufc = 0L
    private var pfl = 0L
    private var jiri = 0L

    private fun Connection.insertFighter(
        name: String,
        division: String?,
        org: Long,
    ): Long =
        prepareStatement(
            "INSERT INTO fighter (name, normalized_name, sex, division, organization_id) VALUES (?, ?, 'M', ?, ?) RETURNING id",
        ).use { ps ->
            ps.setString(1, name)
            ps.setString(2, NameNormalizer.normalize(name))
            ps.setString(3, division)
            ps.setLong(4, org)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun Connection.orgId(code: String): Long =
        prepareStatement("SELECT id FROM organization WHERE code = ?").use { ps ->
            ps.setString(1, code)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    @BeforeEach
    fun seed() {
        tx.executeWrite { st ->
            val c = st.connection
            c.createStatement().use {
                it.execute("DELETE FROM fighter_external_id; DELETE FROM fighter")
            }
            ufc = c.orgId("UFC")
            pfl = c.orgId("PFL")
            jiri = c.insertFighter("Jiří Procházka", "Light Heavyweight", ufc)
            c.insertFighter("Dup Fighter", "Lightweight", ufc)
            c.insertFighter("Dup Fighter", "Welterweight", ufc)
            c.insertFighter("Jiří Procházka", "Light Heavyweight", pfl)
            c.prepareStatement("INSERT INTO fighter_external_id VALUES (?, 'ufcstats', 'abc')").use {
                it.setLong(1, jiri)
                it.executeUpdate()
            }
        }
    }

    private fun match(ref: IngestPayload.FighterRef): FighterMatch =
        tx.executeRead { matcher.match(it.connection, "ufcstats", ufc, ref) }

    @Test
    fun `normalization strips diacritics and punctuation`() {
        assertEquals("jiri prochazka", NameNormalizer.normalize("  Jiří  Procházka "))
        assertEquals("o malley", NameNormalizer.normalize("O'Malley"))
    }

    @Test
    fun `external id wins even with a different name`() {
        assertEquals(
            FighterMatch.Matched(jiri),
            match(IngestPayload.FighterRef(externalId = "abc", name = "Someone Else")),
        )
    }

    @Test
    fun `unique name with confirming division matches, ignoring diacritics and order`() {
        val expected = FighterMatch.Matched(jiri)
        assertEquals(expected, match(IngestPayload.FighterRef(name = "Jiri Prochazka", division = "Light Heavyweight")))
        assertEquals(expected, match(IngestPayload.FighterRef(name = "Prochazka Jiri", division = "light heavyweight")))
    }

    @Test
    fun `name without confirming division is uncertain`() {
        assertEquals(FighterMatch.Uncertain(listOf(jiri)), match(IngestPayload.FighterRef(name = "Jiri Prochazka")))
        assertEquals(
            FighterMatch.Uncertain(listOf(jiri)),
            match(IngestPayload.FighterRef(name = "Jiri Prochazka", division = "Heavyweight")),
        )
    }

    @Test
    fun `same name in several divisions is resolved by division only`() {
        val m = match(IngestPayload.FighterRef(name = "Dup Fighter", division = "Welterweight"))
        assert(m is FighterMatch.Matched)
        assert(match(IngestPayload.FighterRef(name = "Dup Fighter")) is FighterMatch.Uncertain)
    }

    @Test
    fun `unknown fighter and other organization are not found`() {
        assertEquals(FighterMatch.NotFound, match(IngestPayload.FighterRef(name = "Nobody Here")))
        assertEquals(FighterMatch.NotFound, match(IngestPayload.FighterRef(externalId = "zzz")))
    }
}
