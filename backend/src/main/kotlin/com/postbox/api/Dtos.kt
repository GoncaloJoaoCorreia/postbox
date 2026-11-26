package com.postbox.api

import com.postbox.db.UserRecord
import com.postbox.db.UserRole
import kotlinx.serialization.Serializable

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
data class ResetPasswordRequest(val newPassword: String)

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
data class ErrorResponse(val error: String)

fun UserRecord.toResponse() = UserResponse(
    id = id,
    email = email,
    displayName = displayName,
    role = role,
    isActive = isActive,
    createdAt = createdAt,
)
