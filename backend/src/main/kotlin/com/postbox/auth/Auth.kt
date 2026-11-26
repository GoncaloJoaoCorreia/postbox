package com.postbox.auth

import com.postbox.db.UserRepository
import com.postbox.db.UserRole
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.Principal
import io.ktor.server.auth.bearer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database

/** Bearer principal for an authenticated, still-active account. Role comes from the DB, not the token. */
class PostboxPrincipal(
    val userId: String,
    val email: String,
    val role: UserRole,
) : Principal

const val AuthJwt = "auth-jwt"

fun Application.configureAuth(db: Database, users: UserRepository, jwtService: JwtService) {
    install(Authentication) {
        bearer(AuthJwt) {
            authenticate { tokenCredential ->
                val decoded = jwtService.verify(tokenCredential.token) ?: return@authenticate null
                val userId = decoded.subject ?: return@authenticate null
                val user = withContext(Dispatchers.IO) { users.findById(userId) }
                if (user == null || !user.isActive) return@authenticate null
                PostboxPrincipal(user.id, user.email, user.role)
            }
        }
    }
}
