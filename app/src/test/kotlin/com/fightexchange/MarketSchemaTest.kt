package com.fightexchange

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.sql.SQLException
import javax.sql.DataSource

@MicronautTest
class MarketSchemaTest : PostgresTest() {
    @Inject
    lateinit var dataSource: DataSource

    private fun newFighter(name: String): Long {
        exec(
            "INSERT INTO fighter (name, normalized_name, sex, organization_id) " +
                "SELECT '$name', lower('$name'), 'M', id FROM organization WHERE code = 'UFC'",
        )
        return query("SELECT max(id)::text FROM fighter WHERE name = '$name'").single().toLong()
    }

    @Test
    fun `listing defaults to IPO with neutral factors`() {
        val id = newFighter("Market A")
        exec("INSERT INTO market_listing (fighter_id, base_value, price) VALUES ($id, 6000000, 6000000)")
        assertEquals(
            listOf("IPO 1.000000 1.000000"),
            query(
                "SELECT status || ' ' || event_multiplier || ' ' || demand_factor FROM market_listing WHERE fighter_id = $id",
            ),
        )
    }

    @Test
    fun `delisted status requires delisted_at and vice versa`() {
        val id = newFighter("Market B")
        assertThrows(SQLException::class.java) {
            exec("INSERT INTO market_listing (fighter_id, status, base_value, price) VALUES ($id, 'DELISTED', 1, 1)")
        }
        assertThrows(SQLException::class.java) {
            exec(
                "INSERT INTO market_listing (fighter_id, status, base_value, price, delisted_at) VALUES ($id, 'ACTIVE', 1, 1, now())",
            )
        }
    }

    @Test
    fun `price_event rejects unknown kinds`() {
        val id = newFighter("Market C")
        exec(
            "INSERT INTO price_event (fighter_id, at, kind, price_before, price_after) VALUES ($id, now(), 'ELO', 1, 2)",
        )
        assertThrows(SQLException::class.java) {
            exec(
                "INSERT INTO price_event (fighter_id, at, kind, price_before, price_after) VALUES ($id, now(), 'MAGIC', 1, 2)",
            )
        }
    }

    @Test
    fun `price_daily keeps open and close within low and high`() {
        val id = newFighter("Market D")
        exec("INSERT INTO price_daily VALUES ($id, '2026-01-01', 5, 6, 4, 7)")
        assertThrows(SQLException::class.java) {
            exec("INSERT INTO price_daily VALUES ($id, '2026-01-02', 5, 9, 4, 7)")
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
