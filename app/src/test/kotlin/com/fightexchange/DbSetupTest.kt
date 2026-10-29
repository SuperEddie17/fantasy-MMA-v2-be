package com.fightexchange

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fightexchange.rules.EloStep
import com.fightexchange.rules.Money
import com.fightexchange.rules.RuleSet
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.sql.DataSource
import kotlin.reflect.full.memberProperties

@MicronautTest
class DbSetupTest : PostgresTest() {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `flyway seeds rule set v2 equal to the code defaults`() {
        val json =
            dataSource.connection.use { conn ->
                conn.createStatement().executeQuery("SELECT params::text FROM rule_set WHERE version = 2").use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        val fromDb: Map<String, Any?> =
            jacksonObjectMapper()
                .readValue(json, Map::class.java)
                .mapKeys { it.key as String }
                .mapValues { normalize(it.value) }
        val fromCode = RuleSet::class.memberProperties.associate { it.name to normalize(it.get(RuleSet.V2)) }
        assertEquals(fromCode, fromDb)
    }

    private fun normalize(v: Any?): Any? =
        when (v) {
            is Money -> v.units.toDouble()
            is Number -> v.toDouble()
            is EloStep -> mapOf("above" to v.above, "bump" to v.bump)
            is List<*> -> v.map(::normalize)
            is Map<*, *> -> v.entries.associate { it.key to normalize(it.value) }
            else -> v
        }
}
