package com.postbox.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

class ApiException(val status: HttpStatusCode, val code: String, override val message: String) : Exception(message)

suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
    fields: List<MetadataFieldResponse>? = null,
) = respond(status, ErrorResponse(ApiError(code, message, fields)))
