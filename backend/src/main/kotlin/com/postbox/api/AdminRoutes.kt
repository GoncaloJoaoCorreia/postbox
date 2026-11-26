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
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Role guard for the `/api/v1/admin` route group: non-admin principals get 403. */
internal suspend fun ApplicationCall.requireAdmin(): Boolean {
    val principal = principal<PostboxPrincipal>()
    if (principal?.role != UserRole.ADMIN) {
        respondError(HttpStatusCode.Forbidden, "admin-required", "An admin account is required")
        return false
    }
    return true
}

fun Route.adminRoutes(users: UserRepository, hasher: PasswordHasher) {
    route("/admin") {
        get("/users") {
            if (!call.requireAdmin()) return@get
            val all = withContext(Dispatchers.IO) { users.listAll() }
            call.respond(all.map { it.toResponse() })
        }
        post("/users") {
            if (!call.requireAdmin()) return@post
            val req = call.receive<CreateUserRequest>()
            if (!isValidEmail(req.email)) {
                call.respondError(HttpStatusCode.BadRequest, "invalid-email", "The email address is not valid")
                return@post
            }
            if (req.password.length < MinPasswordLength) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "password-too-short",
                    "The password must be at least $MinPasswordLength characters long",
                )
                return@post
            }
            val emailTaken = withContext(Dispatchers.IO) { users.findByEmail(req.email) != null }
            if (emailTaken) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "email-already-used",
                    "An account with this email already exists",
                )
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
        patch("/users/{id}") {
            if (!call.requireAdmin()) return@patch
            val id = call.parameters["id"] ?: run {
                call.respondError(HttpStatusCode.BadRequest, "missing-id", "The user id path parameter is missing")
                return@patch
            }
            val req = call.receive<UpdateUserRequest>()
            if (req.isActive == null && req.newPassword == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-request",
                    "Provide at least one of isActive, newPassword",
                )
                return@patch
            }
            if (req.newPassword != null && req.newPassword.length < MinPasswordLength) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "password-too-short",
                    "The password must be at least $MinPasswordLength characters long",
                )
                return@patch
            }
            val existing = withContext(Dispatchers.IO) { users.findById(id) } ?: run {
                call.respondError(HttpStatusCode.NotFound, "user-not-found", "No account with this id exists")
                return@patch
            }
            if (req.isActive != null && req.isActive != existing.isActive) {
                withContext(Dispatchers.IO) { users.setActive(id, req.isActive) }
            }
            if (req.newPassword != null) {
                withContext(Dispatchers.IO) { users.updatePasswordHash(id, hasher.hash(req.newPassword)) }
            }
            val updated = withContext(Dispatchers.IO) { users.findById(id) }!!
            call.respond(updated.toResponse())
        }
    }
}
