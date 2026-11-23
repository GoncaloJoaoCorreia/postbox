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

class UserRepository(private val db: Database) {

    /** Stores [passwordHash] verbatim — hashing belongs to the later auth layer (AC-003). */
    fun create(
        email: String,
        passwordHash: String,
        displayName: String,
        role: UserRole = UserRole.USER,
        isActive: Boolean = true,
    ): UserRecord = dbQuery(db) {
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
        UserRecord(
            id = id,
            email = email,
            displayName = displayName,
            role = role,
            isActive = isActive,
            createdAt = createdAt,
        )
    }

    fun findById(id: String): UserRecord? = dbQuery(db) {
        Users.selectAll().where { Users.id eq id }.firstOrNull()?.let(::toUserRecord)
    }

    fun findByEmail(email: String): UserRecord? = dbQuery(db) {
        Users.selectAll().where { Users.email eq email }.firstOrNull()?.let(::toUserRecord)
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

    private fun toUserRecord(row: ResultRow) = UserRecord(
        id = row[Users.id],
        email = row[Users.email],
        displayName = row[Users.displayName],
        role = UserRole.fromDb(row[Users.role]),
        isActive = row[Users.isActive],
        createdAt = row[Users.createdAt],
    )
}
