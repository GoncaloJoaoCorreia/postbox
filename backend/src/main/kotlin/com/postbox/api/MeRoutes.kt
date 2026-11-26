package com.postbox.api

import com.postbox.auth.PasswordHasher
import com.postbox.auth.PostboxPrincipal
import com.postbox.db.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.patch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.meRoutes(users: UserRepository, hasher: PasswordHasher) {
    patch("/me") {
        val principal = call.principal<PostboxPrincipal>()!!
        val req = call.receive<ChangePasswordRequest>()
        if (req.newPassword.length < MinPasswordLength) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("password-too-short"))
            return@patch
        }
        withContext(Dispatchers.IO) {
            users.updatePasswordHash(principal.userId, hasher.hash(req.newPassword))
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
