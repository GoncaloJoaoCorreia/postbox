package com.postbox.api

import com.postbox.auth.PasswordHasher
import com.postbox.auth.PostboxPrincipal
import com.postbox.db.DeviceSessionRepository
import com.postbox.db.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.meRoutes(users: UserRepository, hasher: PasswordHasher, sessions: DeviceSessionRepository) {
    get("/me") {
        val principal = call.principal<PostboxPrincipal>()!!
        val user = withContext(Dispatchers.IO) { users.findById(principal.userId) }!!
        call.respond(user.toResponse())
    }
    patch("/me") {
        val principal = call.principal<PostboxPrincipal>()!!
        val req = call.receive<ChangePasswordRequest>()
        if (req.newPassword.length < MinPasswordLength) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "password-too-short",
                "The password must be at least $MinPasswordLength characters long",
            )
            return@patch
        }
        withContext(Dispatchers.IO) {
            users.updatePasswordHash(principal.userId, hasher.hash(req.newPassword))
            sessions.revokeAllForUser(principal.userId)
        }
        call.respond(HttpStatusCode.NoContent)
    }
    get("/me/devices") {
        val principal = call.principal<PostboxPrincipal>()!!
        val devices = withContext(Dispatchers.IO) { sessions.listForUser(principal.userId) }
        call.respond(devices.map { it.toResponse() })
    }
    delete("/me/devices/{id}") {
        val principal = call.principal<PostboxPrincipal>()!!
        val id = call.parameters["id"] ?: run {
            call.respondError(HttpStatusCode.BadRequest, "missing-id", "The device id path parameter is missing")
            return@delete
        }
        val existing = withContext(Dispatchers.IO) { sessions.findForUser(principal.userId, id) }
        if (existing == null) {
            call.respondError(HttpStatusCode.NotFound, "device-not-found", "No device session with this id exists")
            return@delete
        }
        withContext(Dispatchers.IO) { sessions.revokeForUser(principal.userId, id) }
        call.respond(HttpStatusCode.NoContent)
    }
}
