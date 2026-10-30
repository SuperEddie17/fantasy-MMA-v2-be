package com.fightexchange.api

import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate

enum class MarketSort(
    val orderBy: String,
) {
    PRICE("l.price DESC, f.id"),
    CHANGE_7D("change7d DESC, f.id"),
    ;

    companion object {
        fun parse(value: String?): MarketSort? =
            when (value?.lowercase()) {
                null, "", "price" -> PRICE
                "change7d" -> CHANGE_7D
                else -> null
            }
    }
}

/** Read-only queries behind the public market endpoints. Delisted and merged fighters are not on the overview. */
@Singleton
class MarketQueries(
    private val tx: TransactionOperations<Connection>,
) {
    fun market(
        org: String?,
        division: String?,
        sort: MarketSort,
        offset: Int,
        limit: Int,
    ): MarketPage {
        val where = mutableListOf("f.merged_into_id IS NULL", "l.status <> 'DELISTED'")
        val args = mutableListOf<Any>()
        org?.let {
            where += "o.code = ?"
            args += it.uppercase()
        }
        division?.let {
            where += "f.division = ?"
            args += it
        }
        val rows =
            query(
                """
                SELECT $FIGHTER_COLUMNS, l.status, l.price, ($CHANGE_7D) AS change7d, l.last_fight_at
                FROM market_listing l
                JOIN fighter f ON f.id = l.fighter_id
                JOIN organization o ON o.id = f.organization_id
                WHERE ${where.joinToString(" AND ")}
                ORDER BY ${sort.orderBy}
                LIMIT ? OFFSET ?
                """.trimIndent(),
                *args.toTypedArray(),
                limit + 1,
                offset,
            ) {
                MarketRowDto(
                    fighter = it.fighter(),
                    status = it.getString(8),
                    price = it.getLong(9),
                    change7d = it.getDouble(10),
                    lastFightAt = it.getTimestamp(11)?.toInstant(),
                )
            }
        return MarketPage(rows.take(limit), if (rows.size > limit) offset + limit else null)
    }

    fun detail(fighterId: Long): MarketDetailDto? =
        query(
            """
            SELECT $FIGHTER_COLUMNS, l.status, l.price, l.base_value, l.event_multiplier, l.demand_factor,
                   ($CHANGE_7D), l.listed_at, l.delisted_at, l.last_fight_at,
                   (SELECT rating_after FROM elo_rating WHERE fighter_id = f.id ORDER BY rated_at DESC, bout_id DESC LIMIT 1)
            FROM market_listing l
            JOIN fighter f ON f.id = l.fighter_id
            JOIN organization o ON o.id = f.organization_id
            WHERE l.fighter_id = ?
            """.trimIndent(),
            fighterId,
        ) {
            MarketDetailDto(
                fighter = it.fighter(),
                status = it.getString(8),
                price = it.getLong(9),
                baseValue = it.getLong(10),
                eventMultiplier = it.getDouble(11),
                demandFactor = it.getDouble(12),
                change7d = it.getDouble(13),
                listedAt = it.getTimestamp(14).toInstant(),
                delistedAt = it.getTimestamp(15)?.toInstant(),
                lastFightAt = it.getTimestamp(16)?.toInstant(),
                elo = it.getDouble(17).takeUnless { _ -> it.wasNull() },
            )
        }.firstOrNull()

    /** Daily OHLC, oldest first; `null` when the fighter has no listing. */
    fun history(
        fighterId: Long,
        from: LocalDate?,
        to: LocalDate?,
    ): List<PriceDayDto>? {
        if (!listed(fighterId)) return null
        val where = mutableListOf("fighter_id = ?")
        val args = mutableListOf<Any>(fighterId)
        from?.let {
            where += "day >= ?"
            args += java.sql.Date.valueOf(it)
        }
        to?.let {
            where += "day <= ?"
            args += java.sql.Date.valueOf(it)
        }
        return query(
            "SELECT day, open, close, low, high FROM price_daily WHERE ${where.joinToString(" AND ")} ORDER BY day",
            *args.toTypedArray(),
        ) { PriceDayDto(it.getDate(1).toLocalDate(), it.getLong(2), it.getLong(3), it.getLong(4), it.getLong(5)) }
    }

    /** Price changes with reasons, newest first; the cursor is the `id` of the last item. */
    fun events(
        fighterId: Long,
        after: Long?,
        limit: Int,
    ): Page<PriceEventDto>? {
        if (!listed(fighterId)) return null
        val rows =
            query(
                """
                SELECT id, at, kind, factor, price_before, price_after, bout_id, note
                FROM price_event
                WHERE fighter_id = ? AND id < ?
                ORDER BY id DESC
                LIMIT ?
                """.trimIndent(),
                fighterId,
                after ?: Long.MAX_VALUE,
                limit + 1,
            ) {
                PriceEventDto(
                    id = it.getLong(1),
                    at = it.getTimestamp(2).toInstant(),
                    kind = it.getString(3),
                    factor = it.getDouble(4).takeUnless { _ -> it.wasNull() },
                    priceBefore = it.getLong(5),
                    priceAfter = it.getLong(6),
                    boutId = it.getLong(7).takeUnless { _ -> it.wasNull() },
                    note = it.getString(8),
                )
            }
        return if (rows.size > limit) Page(rows.take(limit), rows[limit - 1].id) else Page(rows, null)
    }

    private fun listed(fighterId: Long): Boolean =
        query("SELECT 1 FROM market_listing WHERE fighter_id = ?", fighterId) { 1 }.isNotEmpty()

    private fun ResultSet.fighter() =
        FighterDto(
            getLong(1),
            getString(2),
            getString(3),
            getString(4),
            getString(5),
            getString(6),
            getString(7),
        )

    private fun <T> query(
        sql: String,
        vararg args: Any,
        map: (ResultSet) -> T,
    ): List<T> =
        tx.executeRead { st ->
            st.connection.prepareStatement(sql).use { ps ->
                ps.bind(args)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) map(rs) else null }.toList() }
            }
        }

    private fun PreparedStatement.bind(args: Array<out Any>) = args.forEachIndexed { i, a -> setObject(i + 1, a) }

    private companion object {
        const val FIGHTER_COLUMNS = "f.id, f.name, f.nickname, f.sex, f.division, o.code, f.country"

        // Price a week ago: the last price after an event up to then; if the listing is younger,
        // the price before its first event; with no events at all, no change.
        const val CHANGE_7D =
            """l.price::double precision / COALESCE(
                (SELECT price_after FROM price_event p WHERE p.fighter_id = l.fighter_id
                    AND p.at <= now() - interval '7 days' ORDER BY p.at DESC, p.id DESC LIMIT 1),
                (SELECT price_before FROM price_event p WHERE p.fighter_id = l.fighter_id
                    ORDER BY p.at, p.id LIMIT 1),
                l.price) - 1"""
    }
}
