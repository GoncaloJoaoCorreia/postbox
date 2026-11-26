package com.postbox.api

import com.postbox.auth.PasswordHasher
import com.postbox.auth.PostboxPrincipal
import com.postbox.db.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.meRoutes(users: UserRepository, hasher: PasswordHasher) {
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
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
