package com.fightexchange.auth

import com.fightexchange.common.ApiException
import io.micronaut.http.HttpStatus
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant

data class User(
    val id: Long,
    val email: String,
    val displayName: String,
    val role: String,
    val createdAt: Instant,
)

private const val UNIQUE_VIOLATION = "23505"
private const val MIN_PASSWORD = 8
private const val MAX_PASSWORD = 200
private const val MAX_NAME = 50
private const val MAX_EMAIL = 254
private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

@Singleton
class UserService(
    private val tx: TransactionOperations<Connection>,
    private val hasher: PasswordHasher,
) {
    private val dummyHash = hasher.hash("dummy-password")

    fun register(
        email: String?,
        password: String?,
        displayName: String?,
    ): User {
        val mail = email?.trim().orEmpty()
        val name = displayName?.trim().orEmpty()
        if (!EMAIL.matches(mail) || mail.length > MAX_EMAIL) invalid("email is not valid")
        if (password == null || password.length < MIN_PASSWORD || password.length > MAX_PASSWORD) {
            invalid("password must have $MIN_PASSWORD to $MAX_PASSWORD characters")
        }
        if (name.isEmpty() || name.length > MAX_NAME) invalid("displayName must have 1 to $MAX_NAME characters")
        val hash = hasher.hash(password)
        return try {
            tx.executeWrite { status ->
                status.connection
                    .prepareStatement(
                        "INSERT INTO app_user (email, password_hash, display_name) VALUES (?, ?, ?) " +
                            "RETURNING id, email, display_name, role, created_at",
                    ).use { ps ->
                        ps.setString(1, mail)
                        ps.setString(2, hash)
                        ps.setString(3, name)
                        ps.executeQuery().use { rs ->
                            rs.next()
                            user(rs)
                        }
                    }
            }
        } catch (e: SQLException) {
            if (e.sqlState == UNIQUE_VIOLATION) {
                throw ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "E-mail is already registered")
            }
            throw e
        }
    }

    /** The user when e-mail and password match, else null. Hashes a dummy password on an unknown e-mail (timing). */
    fun authenticate(
        email: String?,
        password: String?,
    ): User? {
        if (email == null || password == null) return null
        val row =
            tx.executeRead { status ->
                status.connection
                    .prepareStatement(
                        "SELECT id, email, display_name, role, created_at, password_hash " +
                            "FROM app_user WHERE lower(email) = lower(?)",
                    ).use { ps ->
                        ps.setString(1, email.trim())
                        ps.executeQuery().use { rs -> if (rs.next()) user(rs) to rs.getString(6) else null }
                    }
            }
        val ok = hasher.matches(password, row?.second ?: dummyHash)
        return if (ok) row?.first else null
    }

    fun find(id: Long): User? =
        tx.executeRead { status ->
            status.connection
                .prepareStatement("SELECT id, email, display_name, role, created_at FROM app_user WHERE id = ?")
                .use { ps ->
                    ps.setLong(1, id)
                    ps.executeQuery().use { rs -> if (rs.next()) user(rs) else null }
                }
        }

    private fun user(rs: java.sql.ResultSet) =
        User(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant())

    private fun invalid(detail: String): Nothing =
        throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request", detail)
}
