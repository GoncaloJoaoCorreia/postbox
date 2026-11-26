package com.postbox.api

import com.postbox.auth.JwtService
import com.postbox.auth.PasswordHasher
import com.postbox.db.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.authRoutes(users: UserRepository, hasher: PasswordHasher, jwtService: JwtService) {
    val dummyHash = hasher.hash("dummy-${UUID.randomUUID()}")
    post("/auth/login") {
        val req = call.receive<LoginRequest>()
        val credentials = withContext(Dispatchers.IO) { users.findCredentialsByEmail(req.email) }
        val passwordOk = hasher.verify(credentials?.passwordHash ?: dummyHash, req.password)
        if (credentials == null || !passwordOk || !credentials.user.isActive) {
            throw ApiException(HttpStatusCode.Unauthorized, "invalid-credentials", "Invalid email or password")
        }
        call.respond(LoginResponse(token = jwtService.issue(credentials.user)))
    }
}
