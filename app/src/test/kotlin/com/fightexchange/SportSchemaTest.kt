package com.fightexchange

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.sql.SQLException
import javax.sql.DataSource

@MicronautTest
class SportSchemaTest : PostgresTest() {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `five organizations are seeded`() {
        val codes = query("SELECT code FROM organization ORDER BY code")
        assertEquals(listOf("KSW", "OKT", "ONE", "PFL", "UFC"), codes)
    }

    @Test
    fun `bout announced_at is the earliest known announcement`() {
        exec(
            "INSERT INTO fighter (name, normalized_name, sex, organization_id) SELECT 'A', 'a', 'M', id FROM organization WHERE code = 'UFC'",
        )
        exec(
            "INSERT INTO fighter (name, normalized_name, sex, organization_id) SELECT 'B', 'b', 'M', id FROM organization WHERE code = 'UFC'",
        )
        exec(
            "INSERT INTO event (organization_id, name, starts_at, source) SELECT id, 'E', now(), 'ADMIN' FROM organization WHERE code = 'UFC'",
        )
        exec(
            """
            INSERT INTO bout (event_id, fighter_a_id, fighter_b_id, announced_at_seen, announced_at_admin)
            SELECT (SELECT max(id) FROM event), (SELECT max(id) - 1 FROM fighter), (SELECT max(id) FROM fighter),
                   '2026-03-02T10:00:00Z', '2026-03-01T10:00:00Z'
            """.trimIndent(),
        )
        assertEquals(
            listOf("2026-03-01T10:00:00Z"),
            query(
                "SELECT to_char(announced_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM bout ORDER BY id DESC LIMIT 1",
            ),
        )
    }

    @Test
    fun `a bout cannot pit a fighter against themselves`() {
        exec(
            "INSERT INTO fighter (name, normalized_name, sex, organization_id) SELECT 'C', 'c', 'F', id FROM organization WHERE code = 'OKT'",
        )
        exec(
            "INSERT INTO event (organization_id, name, starts_at, source) SELECT id, 'E2', now(), 'ADMIN' FROM organization WHERE code = 'OKT'",
        )
        assertThrows(SQLException::class.java) {
            exec(
                "INSERT INTO bout (event_id, fighter_a_id, fighter_b_id) SELECT (SELECT max(id) FROM event), max(id), max(id) FROM fighter",
            )
        }
    }

    @Test
    fun `ingest events are unique per source and key except duplicates`() {
        val key = "k-${System.nanoTime()}"
        exec(
            "INSERT INTO ingest_event (source, external_key, type, payload) VALUES ('T', '$key', 'fighter.upsert', '{}')",
        )
        exec(
            "INSERT INTO ingest_event (source, external_key, type, payload, status) VALUES ('T', '$key', 'fighter.upsert', '{}', 'DUPLICATE')",
        )
        assertThrows(SQLException::class.java) {
            exec(
                "INSERT INTO ingest_event (source, external_key, type, payload) VALUES ('T', '$key', 'fighter.upsert', '{}')",
            )
        }
    }

    private fun exec(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    private fun query(sql: String): List<String> =
        dataSource.connection.use { conn ->
            conn.createStatement().executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
}
