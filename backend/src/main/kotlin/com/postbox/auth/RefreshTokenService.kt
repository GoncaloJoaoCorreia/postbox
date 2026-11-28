package com.postbox.auth

import com.postbox.db.DeviceSessionRecord
import com.postbox.db.DeviceSessionRepository
import com.postbox.db.RotationResult
import com.postbox.db.UserRecord
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** Outcome of a refresh attempt. */
sealed interface RefreshOutcome {
    data class Rotated(val session: DeviceSessionRecord, val refreshToken: String) : RefreshOutcome
    data object ReuseDetected : RefreshOutcome
    data object Invalid : RefreshOutcome
}

/** The raw refresh token of a session, shown to the client exactly once at issue/rotation time. */
data class IssuedSession(val sessionId: String, val refreshToken: String)

/**
 * Issues and rotates opaque refresh tokens for device sessions. Tokens are 256-bit
 * random values, sent to the client once; only their SHA-256 hash is persisted.
 * Rotation is single-step: the rotated-out hash is kept as `previous_token_hash`,
 * so replaying it is detected and revokes the whole session.
 */
class RefreshTokenService(
    private val sessions: DeviceSessionRepository,
    private val refreshTokenTtl: Duration = 90.days,
) {

    private val random = SecureRandom()

    fun issueSession(user: UserRecord, deviceName: String): IssuedSession {
        val token = newToken()
        val session = sessions.create(user.id, hashToken(token), deviceName, refreshTokenTtl)
        return IssuedSession(sessionId = session.id, refreshToken = token)
    }

    fun refresh(refreshToken: String): RefreshOutcome {
        val newToken = newToken()
        return when (val result = sessions.rotateByToken(hashToken(refreshToken), hashToken(newToken), refreshTokenTtl)) {
            is RotationResult.Rotated -> RefreshOutcome.Rotated(result.session, newToken)
            is RotationResult.ReuseDetected -> RefreshOutcome.ReuseDetected
            RotationResult.Invalid -> RefreshOutcome.Invalid
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
