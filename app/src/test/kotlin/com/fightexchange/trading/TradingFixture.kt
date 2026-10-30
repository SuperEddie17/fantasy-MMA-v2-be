package com.fightexchange.trading

import io.micronaut.transaction.TransactionOperations
import java.sql.Connection
import java.sql.Statement
import java.time.Instant

/** Rows for trading tests, written straight to the database. */
class TradingFixture(
    private val tx: TransactionOperations<Connection>,
) {
    fun clean() {
        exec(
            "DELETE FROM weekly_buy_counter; DELETE FROM ledger_entry; DELETE FROM position_lot; DELETE FROM trade; " +
                "DELETE FROM portfolio; DELETE FROM season; DELETE FROM app_user; DELETE FROM odds_snapshot; " +
                "DELETE FROM price_event; DELETE FROM bout; DELETE FROM event; DELETE FROM market_listing; " +
                "DELETE FROM fighter_external_id; DELETE FROM fighter",
        )
    }

    fun exec(sql: String) = tx.executeWrite { it.connection.createStatement().use { s -> s.execute(sql) } }

    fun insert(sql: String): Long =
        tx.executeWrite { status ->
            status.connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    fun query(sql: String): List<String> =
        tx.executeRead { status ->
            status.connection.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    fun user(email: String = "ann@example.com"): Long =
        insert("INSERT INTO app_user (email, password_hash, display_name) VALUES ('$email', 'x', 'Ann')")

    fun season(
        startsAt: String = "2024-12-01T00:00:00Z",
        endsAt: String = "2026-01-01T00:00:00Z",
    ): Long =
        insert(
            "INSERT INTO season (name, starts_at, ends_at, rule_set_id) " +
                "SELECT 'S', '$startsAt', '$endsAt', id FROM rule_set WHERE version = 2",
        )

    /** A portfolio with [cash] as its starting budget (ledger and cache agree). */
    fun portfolio(
        userId: Long,
        seasonId: Long,
        cash: Long = 100_000_000,
    ): Long {
        val id = insert("INSERT INTO portfolio (user_id, season_id, cash_cached) VALUES ($userId, $seasonId, $cash)")
        exec("INSERT INTO ledger_entry (portfolio_id, at, kind, amount) VALUES ($id, now(), 'START_BUDGET', $cash)")
        return id
    }

    fun fighter(
        name: String,
        price: Long = 6_000_000,
        org: String = "UFC",
        status: String = "ACTIVE",
    ): Long {
        val id =
            insert(
                "INSERT INTO fighter (name, normalized_name, sex, organization_id) " +
                    "SELECT '$name', lower('$name'), 'M', id FROM organization WHERE code = '$org'",
            )
        val delisted = if (status == "DELISTED") ", delisted_at" else ""
        val delistedValue = if (status == "DELISTED") ", now()" else ""
        exec(
            "INSERT INTO market_listing (fighter_id, status, base_value, price$delisted) " +
                "VALUES ($id, '$status', $price, $price$delistedValue)",
        )
        return id
    }

    fun event(
        startsAt: Instant,
        weighInAt: Instant?,
    ): Long =
        insert(
            "INSERT INTO event (organization_id, name, starts_at, weigh_in_at, source) " +
                "SELECT id, 'E', '$startsAt', ${weighInAt?.let {
                    "'$it'"
                } ?: "NULL"}, 'TEST' FROM organization WHERE code = 'UFC'",
        )

    fun bout(
        eventId: Long,
        a: Long,
        b: Long,
        status: String = "ANNOUNCED",
    ): Long =
        insert(
            "INSERT INTO bout (event_id, fighter_a_id, fighter_b_id, status) VALUES ($eventId, $a, $b, '$status')",
        )

    fun cashCached(portfolioId: Long) =
        query(
            "SELECT cash_cached FROM portfolio WHERE id = $portfolioId",
        ).single().toLong()

    fun ledgerSum(portfolioId: Long) =
        query("SELECT COALESCE(sum(amount), 0) FROM ledger_entry WHERE portfolio_id = $portfolioId").single().toLong()
}
