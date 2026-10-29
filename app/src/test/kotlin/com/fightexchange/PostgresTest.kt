package com.fightexchange

import io.micronaut.test.support.TestPropertyProvider
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base for tests that need PostgreSQL: a Testcontainers Postgres 16, or an existing database from
 * TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD (for machines without Docker). Skipped when neither is available.
 */
@EnabledIf("com.fightexchange.PostgresTest#databaseAvailable")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresTest : TestPropertyProvider {
    override fun getProperties(): Map<String, String> {
        val url = System.getenv("TEST_DB_URL")
        if (url != null) {
            return mapOf(
                "datasources.default.url" to url,
                "datasources.default.username" to (System.getenv("TEST_DB_USER") ?: "fightexchange"),
                "datasources.default.password" to (System.getenv("TEST_DB_PASSWORD") ?: ""),
            )
        }
        postgres.start()
        return mapOf(
            "datasources.default.url" to postgres.jdbcUrl,
            "datasources.default.username" to postgres.username,
            "datasources.default.password" to postgres.password,
        )
    }

    companion object {
        private val postgres: PostgreSQLContainer<*> by lazy { PostgreSQLContainer("postgres:16") }

        @JvmStatic
        fun databaseAvailable(): Boolean =
            System.getenv("TEST_DB_URL") != null ||
                runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }
}
