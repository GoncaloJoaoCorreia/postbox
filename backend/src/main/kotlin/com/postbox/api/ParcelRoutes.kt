package com.postbox.api

import com.postbox.auth.PostboxPrincipal
import com.postbox.carrier.CarrierError
import com.postbox.carrier.CarrierId
import com.postbox.carrier.MetadataField
import com.postbox.db.ParcelRecord
import com.postbox.db.ParcelRepository
import com.postbox.db.nowIso
import com.postbox.service.DetectionMethod
import com.postbox.service.DetectionResult
import com.postbox.service.DetectionService
import com.postbox.service.ParcelNotFound
import com.postbox.service.RefreshService
import com.postbox.service.TrackingNumberRules
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.parcelRoutes(
    parcels: ParcelRepository,
    detectionService: DetectionService,
    refreshService: RefreshService,
) {
    route("/parcels") {
        get {
            val userId = call.currentUserId()
            val owned = withContext(Dispatchers.IO) { parcels.listParcelsForUser(userId) }
            val refreshed = refreshService.refreshStale(owned)
            val responses = refreshed.map { outcome ->
                outcome.parcel.toApiResponse(parcels, userId, outcome.missingMetadata)
            }
            call.respond(
                responses.sortedWith(
                    compareByDescending<ParcelResponse> { it.latestEvent?.occurredAt ?: "" }
                        .thenByDescending { it.createdAt },
                ),
            )
        }
        post {
            val req = call.receive<CreateParcelRequest>()
            val trackingNumber = normalizeTrackingNumber(req.trackingNumber)
            if (trackingNumber == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-tracking-number",
                    "Tracking number must be 1-$MaxTrackingNumberLength characters of letters, digits or hyphens",
                )
                return@post
            }
            if (req.displayName != null && !isValidDisplayName(req.displayName)) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-display-name",
                    "Display name must be 1-$MaxDisplayNameLength characters",
                )
                return@post
            }
            val carrier = req.carrier?.let {
                CarrierId.fromId(it) ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "invalid-carrier", "Unknown carrier: $it")
                    return@post
                }
            }
            val detection = if (carrier != null) {
                DetectionResult(carrier, DetectionMethod.OVERRIDE, TrackingNumberRules.candidatesFor(trackingNumber))
            } else {
                detectionService.detect(trackingNumber, null, req.metadata ?: emptyMap())
            }
            val created = withContext(Dispatchers.IO) {
                parcels.createParcel(
                    userId = call.currentUserId(),
                    trackingNumber = trackingNumber,
                    displayName = req.displayName ?: trackingNumber,
                    status = "UNKNOWN",
                    carrier = detection.carrier?.id,
                    detectionMethod = detection.method.name.lowercase(),
                    carrierMetadata = req.metadata?.let { Json.encodeToString(it) },
                )
            }
            call.respond(HttpStatusCode.Created, created.toResponse())
        }
        post("/detect") {
            val req = call.receive<DetectRequest>()
            val trackingNumber = normalizeTrackingNumber(req.trackingNumber)
            if (trackingNumber == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid-tracking-number",
                    "Tracking number must be 1-$MaxTrackingNumberLength characters of letters, digits or hyphens",
                )
                return@post
            }
            val carrier = req.carrier?.let {
                CarrierId.fromId(it) ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "invalid-carrier", "Unknown carrier: $it")
                    return@post
                }
            }
            val result = detectionService.detect(trackingNumber, carrier, req.metadata ?: emptyMap())
            call.respond(
                DetectResponse(
                    trackingNumber = trackingNumber,
                    carrier = result.carrier?.id,
                    method = result.method.name.lowercase(),
                    candidates = result.candidates.map { it.id },
                ),
            )
        }
        route("/{id}") {
            get {
                val parcel = call.ownedParcel(parcels) ?: return@get call.parcelNotFound()
                call.respond(parcel.toApiResponse(parcels, call.currentUserId()))
            }
            patch {
                val parcel = call.ownedParcel(parcels) ?: return@patch call.parcelNotFound()
                val req = call.receive<UpdateParcelRequest>()
                if (req.displayName != null && !isValidDisplayName(req.displayName)) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid-display-name",
                        "Display name must be 1-$MaxDisplayNameLength characters",
                    )
                    return@patch
                }
                val carrier = req.carrier?.let {
                    CarrierId.fromId(it) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "invalid-carrier", "Unknown carrier: $it")
                        return@patch
                    }
                }
                val userId = call.currentUserId()
                val archivedAt = when (req.archived) {
                    null -> parcel.archivedAt
                    true -> nowIso()
                    false -> null
                }
                withContext(Dispatchers.IO) {
                    parcels.updateParcelForUser(
                        userId = userId,
                        parcelId = parcel.id,
                        displayName = req.displayName ?: parcel.displayName,
                        status = parcel.status,
                        carrier = carrier?.id ?: parcel.carrier,
                        detectionMethod = if (carrier != null) "override" else parcel.detectionMethod,
                        carrierMetadata = req.metadata?.let { Json.encodeToString(it) } ?: parcel.carrierMetadata,
                        lastCheckedAt = parcel.lastCheckedAt,
                        archivedAt = archivedAt,
                        lastCheckError = parcel.lastCheckError,
                        lastCheckErrorCode = parcel.lastCheckErrorCode,
                        rateLimitedUntil = parcel.rateLimitedUntil,
                        estimatedDeliveryAt = parcel.estimatedDeliveryAt,
                    )
                }
                val reloaded = withContext(Dispatchers.IO) { parcels.findParcelForUser(userId, parcel.id) }!!
                call.respond(reloaded.toApiResponse(parcels, userId))
            }
            delete {
                val parcel = call.ownedParcel(parcels) ?: return@delete call.parcelNotFound()
                withContext(Dispatchers.IO) { parcels.deleteParcelForUser(call.currentUserId(), parcel.id) }
                call.respond(HttpStatusCode.NoContent)
            }
            get("/events") {
                val parcel = call.ownedParcel(parcels) ?: return@get call.parcelNotFound()
                val events = withContext(Dispatchers.IO) { parcels.listEventsForUser(call.currentUserId(), parcel.id) }
                call.respond(events.asReversed().map { it.toResponse() })
            }
            post("/refresh") {
                val parcel = call.ownedParcel(parcels) ?: return@post call.parcelNotFound()
                val userId = call.currentUserId()
                try {
                    val refreshed = refreshService.refresh(userId, parcel.id)
                    call.respond(refreshed.toApiResponse(parcels, userId))
                } catch (e: ParcelNotFound) {
                    call.parcelNotFound()
                } catch (e: CarrierError.NeedsMetadata) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "missing-carrier-metadata",
                        "The carrier requires additional metadata: ${e.fields.joinToString(", ") { it.name }}",
                        fields = e.fields.map { it.toResponse() },
                    )
                }
            }
        }
    }
}

private suspend fun ApplicationCall.currentUserId(): String = principal<PostboxPrincipal>()!!.userId

private suspend fun ApplicationCall.ownedParcel(parcels: ParcelRepository): ParcelRecord? {
    val id = parameters["id"] ?: return null
    return withContext(Dispatchers.IO) { parcels.findParcelForUser(currentUserId(), id) }
}

private suspend fun ApplicationCall.parcelNotFound() =
    respondError(HttpStatusCode.NotFound, "parcel-not-found", "No parcel with this id exists")

private suspend fun ParcelRecord.toApiResponse(
    parcels: ParcelRepository,
    userId: String,
    missingMetadata: List<MetadataField> = emptyList(),
): ParcelResponse {
    val latestEvent = withContext(Dispatchers.IO) { parcels.findLatestEventForUser(userId, id) }
    return toResponse(latestEvent, missingMetadata.ifEmpty { null })
}
