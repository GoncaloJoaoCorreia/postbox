package com.postbox.api

import com.postbox.auth.PasswordHasher
import com.postbox.auth.PostboxPrincipal
import com.postbox.db.UserRepository
import com.postbox.db.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Role guard for the `/api/v1/admin` route group: non-admin principals get 403. */
internal suspend fun ApplicationCall.requireAdmin(): Boolean {
    val principal = principal<PostboxPrincipal>()
    if (principal?.role != UserRole.ADMIN) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("admin-required"))
        return false
    }
    return true
}

fun Route.adminRoutes(users: UserRepository, hasher: PasswordHasher) {
    route("/admin") {
        post("/users") {
            if (!call.requireAdmin()) return@post
            val req = call.receive<CreateUserRequest>()
            if (!isValidEmail(req.email)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid-email"))
                return@post
            }
            if (req.password.length < MinPasswordLength) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("password-too-short"))
                return@post
            }
            val emailTaken = withContext(Dispatchers.IO) { users.findByEmail(req.email) != null }
            if (emailTaken) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("email-already-used"))
                return@post
            }
            val created = withContext(Dispatchers.IO) {
                users.create(
                    req.email,
                    hasher.hash(req.password),
                    req.displayName ?: displayNameFor(req.email),
                    role = req.role,
                )
            }
            call.respond(HttpStatusCode.Created, created.toResponse())
        }
        post("/users/{id}/deactivate") {
            if (!call.requireAdmin()) return@post
            setUserActive(call, users, active = false)
        }
        post("/users/{id}/reactivate") {
            if (!call.requireAdmin()) return@post
            setUserActive(call, users, active = true)
        }
        post("/users/{id}/reset-password") {
            if (!call.requireAdmin()) return@post
            val id = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing-id"))
                return@post
            }
            val req = call.receive<ResetPasswordRequest>()
            if (req.newPassword.length < MinPasswordLength) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("password-too-short"))
                return@post
            }
            val rows = withContext(Dispatchers.IO) {
                users.updatePasswordHash(id, hasher.hash(req.newPassword))
            }
            if (rows == 0) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("user-not-found"))
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private suspend fun setUserActive(
    call: ApplicationCall,
    users: UserRepository,
    active: Boolean,
) {
    val id = call.parameters["id"] ?: run {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing-id"))
        return
    }
    val rows = withContext(Dispatchers.IO) { users.setActive(id, active) }
    if (rows == 0) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("user-not-found"))
        return
    }
    val user = withContext(Dispatchers.IO) { users.findById(id) }!!
    call.respond(user.toResponse())
}
