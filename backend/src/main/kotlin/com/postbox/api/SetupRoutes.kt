package com.postbox.api

import com.postbox.auth.PasswordHasher
import com.postbox.db.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.setupRoutes(users: UserRepository, hasher: PasswordHasher) {
    get("/setup/status") {
        val pending = withContext(Dispatchers.IO) { users.countAll() == 0L }
        call.respond(SetupStatusResponse(setupPending = pending))
    }
    post("/setup") {
        val req = call.receive<SetupRequest>()
        if (!isValidEmail(req.email)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid-email"))
            return@post
        }
        if (req.password.length < MinPasswordLength) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("password-too-short"))
            return@post
        }
        val admin = withContext(Dispatchers.IO) {
            users.createFirstAdminIfNoUsers(req.email, hasher.hash(req.password), displayNameFor(req.email))
        }
        if (admin == null) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("setup-already-completed"))
        } else {
            call.respond(HttpStatusCode.Created, admin.toResponse())
        }
    }
}
