package com.fightexchange.auth

import jakarta.inject.Singleton
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256 with a random salt; stored as `pbkdf2$<iterations>$<salt>$<hash>` (Base64). */
@Singleton
class PasswordHasher {
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return "pbkdf2\$$ITERATIONS\$${b64(salt)}\$${b64(derive(password, salt, ITERATIONS))}"
    }

    fun matches(
        password: String,
        stored: String?,
    ): Boolean {
        val parts = stored?.split('$')?.takeIf { it.size == PARTS && it[0] == "pbkdf2" } ?: return false
        val iterations = parts[1].toIntOrNull()
        val salt = decode(parts[2])
        val expected = decode(parts[3])
        return iterations != null &&
            salt != null &&
            expected != null &&
            MessageDigest.isEqual(derive(password, salt, iterations), expected)
    }

    private fun decode(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()

    private fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray =
        SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS))
            .encoded

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val ITERATIONS = 210_000
        const val SALT_BYTES = 16
        const val KEY_BITS = 256
        const val PARTS = 4
    }
}
