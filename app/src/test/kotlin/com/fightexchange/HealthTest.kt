package com.fightexchange

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@MicronautTest
class HealthTest : PostgresTest() {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `health endpoint is up including the database`() {
        val response = client.toBlocking().exchange(HttpRequest.GET<Any>("/health"), String::class.java)
        assertEquals(HttpStatus.OK, response.status)
        val body = response.body()!!
        assertTrue(body.contains("\"status\":\"UP\""), body)
        assertTrue(body.contains("\"jdbc\""), body)
    }
}
