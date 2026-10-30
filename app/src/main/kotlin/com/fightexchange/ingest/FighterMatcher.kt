package com.fightexchange.ingest

import jakarta.inject.Singleton
import java.sql.Connection
import java.text.Normalizer

/** Result of resolving a [IngestPayload.FighterRef] to a stored fighter. */
sealed interface FighterMatch {
    /** Certain match (external id, or a unique name match confirmed by the division). */
    data class Matched(
        val fighterId: Long,
    ) : FighterMatch

    /** Name matches but the division does not confirm it, or several fighters match: needs an admin. */
    data class Uncertain(
        val candidateIds: List<Long>,
    ) : FighterMatch

    data object NotFound : FighterMatch
}

object NameNormalizer {
    private val combiningMarks = Regex("\\p{M}+")
    private val nonAlphanumeric = Regex("[^a-z0-9]+")

    /** Lower case, no diacritics, punctuation turned into single spaces. */
    fun normalize(name: String): String =
        Normalizer
            .normalize(name, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase()
            .replace(nonAlphanumeric, " ")
            .trim()

    /** Normalized name plus the variant with the order of the names reversed ("Silva Anderson"). */
    fun variants(name: String): List<String> {
        val normalized = normalize(name)
        val reversed = normalized.split(' ').reversed().joinToString(" ")
        return listOf(normalized, reversed).distinct()
    }
}

/** Resolves fighter references from sources: first `fighter_external_id`, then normalized name + division. */
@Singleton
class FighterMatcher {
    fun match(
        conn: Connection,
        source: String,
        organizationId: Long,
        ref: IngestPayload.FighterRef,
    ): FighterMatch {
        ref.externalId?.let { externalId ->
            findByExternalId(conn, source, externalId)?.let { return FighterMatch.Matched(it) }
        }
        val name = ref.name?.takeIf { NameNormalizer.normalize(it).isNotEmpty() } ?: return FighterMatch.NotFound
        val candidates = findByName(conn, organizationId, NameNormalizer.variants(name))
        val division = ref.division
        val confirmed = candidates.filter { division != null && it.division.equals(division, ignoreCase = true) }
        return when {
            candidates.isEmpty() -> FighterMatch.NotFound
            confirmed.size == 1 -> FighterMatch.Matched(confirmed.single().id)
            else -> FighterMatch.Uncertain(candidates.map { it.id })
        }
    }

    private class Candidate(
        val id: Long,
        val division: String?,
    )

    private fun findByExternalId(
        conn: Connection,
        source: String,
        externalId: String,
    ): Long? =
        conn
            .prepareStatement(
                "SELECT COALESCE(f.merged_into_id, f.id) FROM fighter_external_id e " +
                    "JOIN fighter f ON f.id = e.fighter_id WHERE e.source = ? AND e.external_id = ?",
            ).use { ps ->
                ps.setString(1, source)
                ps.setString(2, externalId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }

    private fun findByName(
        conn: Connection,
        organizationId: Long,
        variants: List<String>,
    ): List<Candidate> =
        conn
            .prepareStatement(
                "SELECT id, division FROM fighter WHERE organization_id = ? AND merged_into_id IS NULL " +
                    "AND normalized_name = ANY (?) ORDER BY id",
            ).use { ps ->
                ps.setLong(1, organizationId)
                ps.setArray(2, conn.createArrayOf("text", variants.toTypedArray()))
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(Candidate(rs.getLong(1), rs.getString(2))) }
                }
            }
}
