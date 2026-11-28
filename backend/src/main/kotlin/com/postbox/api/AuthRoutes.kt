package com.postbox.api

import com.postbox.auth.JwtService
import com.postbox.auth.PasswordHasher
import com.postbox.auth.RefreshOutcome
import com.postbox.auth.RefreshTokenService
import com.postbox.db.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.authRoutes(
    users: UserRepository,
    hasher: PasswordHasher,
    jwtService: JwtService,
    refreshTokens: RefreshTokenService,
) {
    val dummyHash = hasher.hash("dummy-${UUID.randomUUID()}")
    post("/auth/login") {
        val req = call.receive<LoginRequest>()
        val deviceName = req.deviceName?.trim()
        if (deviceName != null && (deviceName.isEmpty() || deviceName.length > MaxDisplayNameLength)) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid-device-name",
                "The device name must be 1-$MaxDisplayNameLength characters long",
            )
            return@post
        }
        val credentials = withContext(Dispatchers.IO) { users.findCredentialsByEmail(req.email) }
        val passwordOk = hasher.verify(credentials?.passwordHash ?: dummyHash, req.password)
        if (credentials == null || !passwordOk || !credentials.user.isActive) {
            throw ApiException(HttpStatusCode.Unauthorized, "invalid-credentials", "Invalid email or password")
        }
        val issued = withContext(Dispatchers.IO) {
            refreshTokens.issueSession(credentials.user, deviceName ?: "Unknown device")
        }
        call.respond(
            LoginResponse(
                token = jwtService.issue(credentials.user),
                refreshToken = issued.refreshToken,
                sessionId = issued.sessionId,
            )
        )
    }
    post("/auth/refresh") {
        val req = call.receive<RefreshRequest>()
        val outcome = withContext(Dispatchers.IO) { refreshTokens.refresh(req.refreshToken) }
        if (outcome !is RefreshOutcome.Rotated) {
            throw ApiException(
                HttpStatusCode.Unauthorized,
                "invalid-refresh-token",
                "The refresh token is invalid, expired or revoked",
            )
        }
        val user = withContext(Dispatchers.IO) { users.findById(outcome.session.userId) }
        if (user == null || !user.isActive) {
            throw ApiException(
                HttpStatusCode.Unauthorized,
                "invalid-refresh-token",
                "The refresh token is invalid, expired or revoked",
            )
        }
        call.respond(
            LoginResponse(
                token = jwtService.issue(user),
                refreshToken = outcome.refreshToken,
                sessionId = outcome.session.id,
            )
        )
    }
}
