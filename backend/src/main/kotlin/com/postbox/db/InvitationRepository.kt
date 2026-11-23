package com.postbox.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** Read model for an invitation row. */
data class InvitationRecord(
    val id: String,
    val token: String,
    val createdBy: String?,
    val expiresAt: String,
    val usedAt: String?,
)

class InvitationRepository(private val db: Database) {

    /**
     * [createdBy] stays nullable so the first invite can be CLI-created before any user exists.
     * [expiresAt] must be fixed-width ISO-8601 UTC text (see [isoUtc]) — [redeem] compares
     * `expires_at` against [nowIso] as plain TEXT, which is only chronological at fixed width.
     */
    fun create(token: String, createdBy: String?, expiresAt: String): InvitationRecord = dbQuery(db) {
        val id = UUID.randomUUID().toString()
        Invitations.insert {
            it[Invitations.id] = id
            it[Invitations.token] = token
            it[Invitations.createdBy] = createdBy
            it[Invitations.expiresAt] = expiresAt
        }
        InvitationRecord(id = id, token = token, createdBy = createdBy, expiresAt = expiresAt, usedAt = null)
    }

    fun findByToken(token: String): InvitationRecord? = dbQuery(db) {
        Invitations.selectAll().where { Invitations.token eq token }.firstOrNull()?.let(::toInvitationRecord)
    }

    /**
     * Single-use redemption: one atomic UPDATE matching only unused, unexpired tokens.
     * Returns the redeemed record, or null when the token is unknown, already used
     * (`used_at` set) or expired (`expires_at` in the past).
     */
    fun redeem(token: String): InvitationRecord? = dbQuery(db) {
        val now = nowIso()
        val updated = Invitations.update({
            (Invitations.token eq token) and
                (Invitations.usedAt.isNull()) and
                (Invitations.expiresAt greater now)
        }) {
            it[Invitations.usedAt] = now
        }
        if (updated == 1) {
            Invitations.selectAll().where { Invitations.token eq token }.first().let(::toInvitationRecord)
        } else {
            null
        }
    }

    private fun toInvitationRecord(row: ResultRow) = InvitationRecord(
        id = row[Invitations.id],
        token = row[Invitations.token],
        createdBy = row[Invitations.createdBy],
        expiresAt = row[Invitations.expiresAt],
        usedAt = row[Invitations.usedAt],
    )
}
