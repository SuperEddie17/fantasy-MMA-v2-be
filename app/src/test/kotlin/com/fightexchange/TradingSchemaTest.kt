package com.fightexchange

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException

@MicronautTest(transactional = false)
class TradingSchemaTest : PostgresTest() {
    @Inject
    lateinit var tx: TransactionOperations<Connection>

    private var userId = 0L
    private var seasonId = 0L
    private var portfolioId = 0L
    private var fighterId = 0L

    @BeforeEach
    @AfterEach
    fun clean() {
        exec(
            "DELETE FROM leaderboard_entry; DELETE FROM net_worth_snapshot; DELETE FROM dividend_payout; " +
                "DELETE FROM captain_pick; DELETE FROM weekly_buy_counter; DELETE FROM ledger_entry; " +
                "DELETE FROM position_lot; DELETE FROM trade; " +
                "DELETE FROM portfolio; DELETE FROM season; DELETE FROM app_user; DELETE FROM fighter",
        )
    }

    private fun seed() {
        userId = insert("INSERT INTO app_user (email, password_hash, display_name) VALUES ('A@Example.com', 'x', 'A')")
        seasonId =
            insert(
                "INSERT INTO season (name, starts_at, ends_at, rule_set_id) " +
                    "SELECT '2026', '2026-01-01', '2027-01-01', id FROM rule_set WHERE version = 2",
            )
        portfolioId = insert("INSERT INTO portfolio (user_id, season_id) VALUES ($userId, $seasonId)")
        fighterId =
            insert(
                "INSERT INTO fighter (name, normalized_name, sex, organization_id) " +
                    "SELECT 'T', 't', 'M', id FROM organization WHERE code = 'UFC'",
            )
    }

    @Test
    fun `e-mail is unique ignoring case`() {
        seed()
        assertThrows(SQLException::class.java) {
            exec("INSERT INTO app_user (email, password_hash, display_name) VALUES ('a@example.COM', 'x', 'B')")
        }
    }

    @Test
    fun `a user has one global portfolio per season`() {
        seed()
        assertThrows(SQLException::class.java) {
            exec("INSERT INTO portfolio (user_id, season_id) VALUES ($userId, $seasonId)")
        }
        exec("INSERT INTO portfolio (user_id, season_id, league_id) VALUES ($userId, $seasonId, 7)")
    }

    @Test
    fun `a season must end after it starts`() {
        seed()
        assertThrows(SQLException::class.java) {
            exec(
                "INSERT INTO season (name, starts_at, ends_at, rule_set_id) " +
                    "SELECT 'bad', '2026-01-02', '2026-01-01', id FROM rule_set LIMIT 1",
            )
        }
    }

    @Test
    fun `idempotency key is unique per portfolio and trade kinds are checked`() {
        seed()
        val sql =
            "INSERT INTO trade (portfolio_id, fighter_id, kind, price, at, idempotency_key) " +
                "VALUES ($portfolioId, $fighterId, 'BUY', 6000000, now(), 'k1')"
        exec(sql)
        assertThrows(SQLException::class.java) { exec(sql) }
        exec(sql.replace("'k1'", "NULL"))
        exec(sql.replace("'k1'", "NULL"))
        assertThrows(SQLException::class.java) { exec(sql.replace("'BUY'", "'GIFT'").replace("'k1'", "'k2'")) }
    }

    @Test
    fun `an open position per portfolio, fighter and side is unique and close fields come together`() {
        seed()
        val open =
            "INSERT INTO position_lot (portfolio_id, fighter_id, side, opened_at, open_price) " +
                "VALUES ($portfolioId, $fighterId, 'LONG', now(), 6000000)"
        exec(open)
        assertThrows(SQLException::class.java) { exec(open) }
        exec(open.replace("'LONG'", "'SHORT'"))
        assertThrows(SQLException::class.java) { exec("UPDATE position_lot SET closed_at = now()") }
        exec("UPDATE position_lot SET closed_at = now(), close_price = 6100000 WHERE side = 'LONG'")
        exec(open)
    }

    @Test
    fun `a ledger entry can be reversed only once`() {
        seed()
        val entry =
            insert(
                "INSERT INTO ledger_entry (portfolio_id, at, kind, amount) VALUES ($portfolioId, now(), 'DIVIDEND', 5)",
            )
        val reversal =
            "INSERT INTO ledger_entry (portfolio_id, at, kind, amount, reverses_id) " +
                "VALUES ($portfolioId, now(), 'REVERSAL', -5, $entry)"
        exec(reversal)
        assertThrows(SQLException::class.java) { exec(reversal) }
        assertEquals(
            0L,
            query("SELECT sum(amount) FROM ledger_entry WHERE portfolio_id = $portfolioId").single().toLong(),
        )
    }

    private fun insert(sql: String): Long =
        tx.executeWrite { status ->
            status.connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun exec(sql: String) = tx.executeWrite { it.connection.createStatement().use { s -> s.execute(sql) } }

    private fun query(sql: String): List<String> =
        tx.executeRead { status ->
            status.connection.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
}
