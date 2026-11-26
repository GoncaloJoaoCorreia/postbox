package com.postbox.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** Account role, stored as TEXT in `users.role` (`USER` | `ADMIN`). */
enum class UserRole(val dbValue: String) {
    USER("USER"),
    ADMIN("ADMIN");

    companion object {
        fun fromDb(value: String): UserRole =
            entries.firstOrNull { it.dbValue == value }
                ?: error("Unknown role in users.role: $value")
    }
}

/** Read model for a user row. Deliberately exposes no password material. */
data class UserRecord(
    val id: String,
    val email: String,
    val displayName: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: String,
)

/** A user row plus its password hash, for the login path only. */
data class UserCredentials(
    val user: UserRecord,
    val passwordHash: String,
)

class UserRepository(private val db: Database) {

    /** Stores [passwordHash] verbatim — hashing belongs to the auth layer. */
    fun create(
        email: String,
        passwordHash: String,
        displayName: String,
        role: UserRole = UserRole.USER,
        isActive: Boolean = true,
    ): UserRecord = dbQuery(db) { insertUser(email, passwordHash, displayName, role, isActive) }

    /**
     * Creates the first ADMIN account, but only while the users table is still
     * empty; the check and the insert run in one transaction so concurrent
     * first-run requests cannot create two admins. Returns null when any
     * account already exists.
     */
    fun createFirstAdminIfNoUsers(
        email: String,
        passwordHash: String,
        displayName: String,
    ): UserRecord? = dbQuery(db) {
        if (Users.selectAll().count() > 0L) return@dbQuery null
        insertUser(email, passwordHash, displayName, UserRole.ADMIN, true)
    }

    fun countAll(): Long = dbQuery(db) { Users.selectAll().count() }

    fun listAll(): List<UserRecord> = dbQuery(db) {
        Users.selectAll().orderBy(Users.createdAt).map(::toUserRecord)
    }

    fun findById(id: String): UserRecord? = dbQuery(db) {
        Users.selectAll().where { Users.id eq id }.firstOrNull()?.let(::toUserRecord)
    }

    fun findByEmail(email: String): UserRecord? = dbQuery(db) {
        Users.selectAll().where { Users.email eq email }.firstOrNull()?.let(::toUserRecord)
    }

    fun findCredentialsByEmail(email: String): UserCredentials? = dbQuery(db) {
        Users.selectAll().where { Users.email eq email }.firstOrNull()?.let { row ->
            UserCredentials(toUserRecord(row), row[Users.passwordHash])
        }
    }

    /**
     * Activates or deactivates an account (admin user management). Returns affected
     * rows — zero when the user does not exist.
     */
    fun setActive(id: String, isActive: Boolean): Int = dbQuery(db) {
        Users.update({ Users.id eq id }) {
            it[Users.isActive] = isActive
        }
    }

    /**
     * Replaces the stored password hash (self-service change, admin reset).
     * Returns affected rows — zero when the user does not exist.
     */
    fun updatePasswordHash(id: String, passwordHash: String): Int = dbQuery(db) {
        Users.update({ Users.id eq id }) {
            it[Users.passwordHash] = passwordHash
        }
    }

    private fun insertUser(
        email: String,
        passwordHash: String,
        displayName: String,
        role: UserRole,
        isActive: Boolean,
    ): UserRecord {
        val id = UUID.randomUUID().toString()
        val createdAt = nowIso()
        Users.insert {
            it[Users.id] = id
            it[Users.email] = email
            it[Users.passwordHash] = passwordHash
            it[Users.displayName] = displayName
            it[Users.role] = role.dbValue
            it[Users.isActive] = isActive
            it[Users.createdAt] = createdAt
        }
        return UserRecord(
            id = id,
            email = email,
            displayName = displayName,
            role = role,
            isActive = isActive,
            createdAt = createdAt,
        )
    }

    private fun toUserRecord(row: ResultRow) = UserRecord(
        id = row[Users.id],
        email = row[Users.email],
        displayName = row[Users.displayName],
        role = UserRole.fromDb(row[Users.role]),
        isActive = row[Users.isActive],
        createdAt = row[Users.createdAt],
    )
}
