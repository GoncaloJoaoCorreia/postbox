package com.postbox.api

import com.postbox.db.ParcelEventRecord
import com.postbox.db.ParcelRecord
import com.postbox.db.UserRecord
import com.postbox.db.UserRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class SetupStatusResponse(val setupPending: Boolean)

@Serializable
data class SetupRequest(val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val token: String)

@Serializable
data class CreateUserRequest(
    val email: String,
    val password: String,
    val displayName: String? = null,
    val role: UserRole = UserRole.USER,
)

@Serializable
data class UpdateUserRequest(
    val isActive: Boolean? = null,
    val newPassword: String? = null,
)

@Serializable
data class ChangePasswordRequest(val newPassword: String)

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: String,
)

@Serializable
data class CreateParcelRequest(
    val trackingNumber: String,
    val displayName: String? = null,
    val carrier: String? = null,
    val metadata: Map<String, String>? = null,
)

@Serializable
data class UpdateParcelRequest(
    val displayName: String? = null,
    val archived: Boolean? = null,
    val carrier: String? = null,
    val metadata: Map<String, String>? = null,
)

@Serializable
data class ParcelEventResponse(
    val id: String,
    val occurredAt: String,
    val status: String,
    val description: String,
    val location: String?,
)

@Serializable
data class ParcelResponse(
    val id: String,
    val trackingNumber: String,
    val carrier: String?,
    val detectionMethod: String?,
    val displayName: String,
    val status: String,
    val metadata: Map<String, String>?,
    val lastCheckedAt: String?,
    val createdAt: String,
    val archivedAt: String?,
    val latestEvent: ParcelEventResponse?,
)

@Serializable
data class DetectRequest(
    val trackingNumber: String,
    val carrier: String? = null,
    val metadata: Map<String, String>? = null,
)

@Serializable
data class DetectResponse(
    val trackingNumber: String,
    val carrier: String?,
    val method: String,
    val candidates: List<String>,
)

@Serializable
data class ApiError(val code: String, val message: String)

@Serializable
data class ErrorResponse(val error: ApiError)

fun UserRecord.toResponse() = UserResponse(
    id = id,
    email = email,
    displayName = displayName,
    role = role,
    isActive = isActive,
    createdAt = createdAt,
)

fun ParcelEventRecord.toResponse() = ParcelEventResponse(
    id = id,
    occurredAt = occurredAt,
    status = status,
    description = description,
    location = location,
)

fun ParcelRecord.toResponse(latestEvent: ParcelEventRecord? = null) = ParcelResponse(
    id = id,
    trackingNumber = trackingNumber,
    carrier = carrier,
    detectionMethod = detectionMethod,
    displayName = displayName,
    status = status,
    metadata = carrierMetadata?.let { Json.decodeFromString<Map<String, String>>(it) },
    lastCheckedAt = lastCheckedAt,
    createdAt = createdAt,
    archivedAt = archivedAt,
    latestEvent = latestEvent?.toResponse(),
)
