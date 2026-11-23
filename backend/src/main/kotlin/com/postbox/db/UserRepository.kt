package com.postbox.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** Read model for a user row. Deliberately exposes no password material. */
data class UserRecord(
    val id: String,
    val email: String,
    val createdAt: String,
)

class UserRepository(private val db: Database) {

    /** Stores [passwordHash] verbatim — hashing belongs to the later auth layer (AC-003). */
    fun create(email: String, passwordHash: String): UserRecord = dbQuery(db) {
        val id = UUID.randomUUID().toString()
        val createdAt = nowIso()
        Users.insert {
            it[Users.id] = id
            it[Users.email] = email
            it[Users.passwordHash] = passwordHash
            it[Users.createdAt] = createdAt
        }
        UserRecord(id = id, email = email, createdAt = createdAt)
    }

    fun findById(id: String): UserRecord? = dbQuery(db) {
        Users.selectAll().where { Users.id eq id }.firstOrNull()?.let(::toUserRecord)
    }

    fun findByEmail(email: String): UserRecord? = dbQuery(db) {
        Users.selectAll().where { Users.email eq email }.firstOrNull()?.let(::toUserRecord)
    }

    private fun toUserRecord(row: ResultRow) = UserRecord(
        id = row[Users.id],
        email = row[Users.email],
        createdAt = row[Users.createdAt],
    )
}
