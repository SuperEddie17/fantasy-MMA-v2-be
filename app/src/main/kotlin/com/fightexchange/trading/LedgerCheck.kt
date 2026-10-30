package com.fightexchange.trading

import com.fightexchange.market.tryJobLock
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.sql.Connection

/** A portfolio whose `cash_cached` differs from the sum of its ledger entries. */
@Serdeable
data class LedgerMismatch(
    val portfolioId: Long,
    val cashCached: Long,
    val ledgerSum: Long,
)

const val LEDGER_CHECK_LOCK = "ledger-check"

/**
 * Nightly check that cash is the sum of the ledger (plan.md, F3.7). Every difference is logged as an error;
 * the job never repairs anything, a mismatch is a bug to look at.
 */
@Singleton
open class LedgerCheck(
    private val tx: TransactionOperations<Connection>,
) {
    private val log = LoggerFactory.getLogger(LedgerCheck::class.java)

    @Scheduled(cron = "0 30 2 * * *", zoneId = "UTC")
    open fun nightly() {
        run()
    }

    /** The mismatches, or null when another instance holds the lock. */
    fun run(): List<LedgerMismatch>? =
        tx.executeWrite { status ->
            val conn = status.connection
            if (!tryJobLock(conn, LEDGER_CHECK_LOCK)) return@executeWrite null
            mismatches(conn).also { found ->
                found.forEach {
                    log.error(
                        "Ledger mismatch in portfolio {}: cash_cached {} but ledger sums to {}",
                        it.portfolioId,
                        it.cashCached,
                        it.ledgerSum,
                    )
                }
                log.info("LedgerCheck: {} mismatches", found.size)
            }
        }

    private fun mismatches(conn: Connection): List<LedgerMismatch> =
        conn.createStatement().use { s ->
            s
                .executeQuery(
                    "SELECT p.id, p.cash_cached, COALESCE(sum(l.amount), 0) FROM portfolio p " +
                        "LEFT JOIN ledger_entry l ON l.portfolio_id = p.id GROUP BY p.id, p.cash_cached " +
                        "HAVING p.cash_cached <> COALESCE(sum(l.amount), 0) ORDER BY p.id",
                ).use { rs ->
                    buildList { while (rs.next()) add(LedgerMismatch(rs.getLong(1), rs.getLong(2), rs.getLong(3))) }
                }
        }
}
