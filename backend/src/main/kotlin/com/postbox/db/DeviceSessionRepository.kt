package com.postbox.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/** Read model for a device-session row. Never carries token material. */
data class DeviceSessionRecord(
    val id: String,
    val userId: String,
    val deviceName: String,
    val createdAt: String,
    val lastUsedAt: String,
    val expiresAt: String,
    val revokedAt: String?,
)

/** Outcome of a refresh-token rotation attempt. */
sealed interface RotationResult {
    data class Rotated(val session: DeviceSessionRecord, val newTokenHash: String) : RotationResult
    data class ReuseDetected(val sessionId: String) : RotationResult
    data object Invalid : RotationResult
}

/**
 * Device-session persistence for refresh-token auth. Only SHA-256 hashes of
 * refresh tokens are stored, never the tokens themselves. [rotateByToken]
 * looks up and rotates in one transaction, so two concurrent refreshes with
 * the same token cannot both succeed.
 */
class DeviceSessionRepository(private val db: Database) {

    fun create(userId: String, tokenHash: String, deviceName: String, ttl: Duration): DeviceSessionRecord =
        dbQuery(db) {
            val id = UUID.randomUUID().toString()
            val now = nowIso()
            DeviceSessions.insert {
                it[DeviceSessions.id] = id
                it[DeviceSessions.userId] = userId
                it[DeviceSessions.tokenHash] = tokenHash
                it[DeviceSessions.deviceName] = deviceName
                it[DeviceSessions.createdAt] = now
                it[DeviceSessions.lastUsedAt] = now
                it[DeviceSessions.expiresAt] = isoUtc(Instant.now().plusSeconds(ttl.inWholeSeconds))
            }
            DeviceSessions.selectAll().where { DeviceSessions.id eq id }.first().let(::toRecord)
        }

    /**
     * Rotates the session whose current token hash is [currentTokenHash]:
     * the presented hash becomes `previous_token_hash` and the session gets
     * [newTokenHash], a fresh `last_used_at` and a sliding `expires_at`.
     * A presented hash matching only `previous_token_hash` means an
     * already-rotated token was replayed, so the whole session is revoked.
     * Revoked, expired or unknown hashes are [RotationResult.Invalid].
     */
    fun rotateByToken(currentTokenHash: String, newTokenHash: String, ttl: Duration): RotationResult =
        dbQuery(db) {
            val now = nowIso()
            val current = DeviceSessions.selectAll()
                .where { DeviceSessions.tokenHash eq currentTokenHash }
                .firstOrNull()
            if (current != null) {
                val rowRevokedAt = current[DeviceSessions.revokedAt]
                val rowExpiresAt = current[DeviceSessions.expiresAt]
                if (rowRevokedAt == null && now < rowExpiresAt) {
                    val newExpiresAt = isoUtc(Instant.now().plusSeconds(ttl.inWholeSeconds))
                    DeviceSessions.update({ DeviceSessions.id eq current[DeviceSessions.id] }) {
                        it[DeviceSessions.previousTokenHash] = currentTokenHash
                        it[DeviceSessions.tokenHash] = newTokenHash
                        it[DeviceSessions.lastUsedAt] = now
                        it[DeviceSessions.expiresAt] = newExpiresAt
                    }
                    return@dbQuery RotationResult.Rotated(
                        toRecord(current).copy(lastUsedAt = now, expiresAt = newExpiresAt),
                        newTokenHash,
                    )
                }
                return@dbQuery RotationResult.Invalid
            }
            val previous = DeviceSessions.selectAll()
                .where { DeviceSessions.previousTokenHash eq currentTokenHash }
                .firstOrNull()
                ?: return@dbQuery RotationResult.Invalid
            if (previous[DeviceSessions.revokedAt] == null) {
                DeviceSessions.update({ DeviceSessions.id eq previous[DeviceSessions.id] }) {
                    it[DeviceSessions.revokedAt] = now
                }
            }
            return@dbQuery RotationResult.ReuseDetected(previous[DeviceSessions.id])
        }

    fun listForUser(userId: String): List<DeviceSessionRecord> = dbQuery(db) {
        DeviceSessions.selectAll()
            .where { DeviceSessions.userId eq userId }
            .orderBy(DeviceSessions.createdAt)
            .map(::toRecord)
    }

    /** Session [sessionId] owned by [userId] — null for other users' ids (same 404 semantics as parcels). */
    fun findForUser(userId: String, sessionId: String): DeviceSessionRecord? = dbQuery(db) {
        DeviceSessions.selectAll()
            .where { (DeviceSessions.userId eq userId) and (DeviceSessions.id eq sessionId) }
            .firstOrNull()?.let(::toRecord)
    }

    /** Revokes one own session; returns affected rows (zero when it does not exist or is already revoked). */
    fun revokeForUser(userId: String, sessionId: String): Int = dbQuery(db) {
        DeviceSessions.update({
            (DeviceSessions.userId eq userId) and (DeviceSessions.id eq sessionId) and
                DeviceSessions.revokedAt.isNull()
        }) {
            it[DeviceSessions.revokedAt] = nowIso()
        }
    }

    /** Revokes all of a user's still-active sessions (password change, admin deactivation). */
    fun revokeAllForUser(userId: String): Int = dbQuery(db) {
        DeviceSessions.update({
            (DeviceSessions.userId eq userId) and DeviceSessions.revokedAt.isNull()
        }) {
            it[DeviceSessions.revokedAt] = nowIso()
        }
    }

    private fun toRecord(row: ResultRow) = DeviceSessionRecord(
        id = row[DeviceSessions.id],
        userId = row[DeviceSessions.userId],
        deviceName = row[DeviceSessions.deviceName],
        createdAt = row[DeviceSessions.createdAt],
        lastUsedAt = row[DeviceSessions.lastUsedAt],
        expiresAt = row[DeviceSessions.expiresAt],
        revokedAt = row[DeviceSessions.revokedAt],
    )
}
