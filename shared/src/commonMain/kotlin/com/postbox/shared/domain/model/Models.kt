package com.postbox.shared.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

enum class TrackingStatus {
    PENDING, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, UNKNOWN
}

enum class Carrier {
    dhl, ctt, postnl, dpd, paack, ecoscooting
}

enum class DetectionMethod {
    rule, probe, override
}

@Serializable
data class MetadataField(
    val name: String,
    val label: String,
    val hint: String,
    val validationRegex: String?
)

@Serializable
data class ParcelEvent(
    val id: String,
    val occurredAt: Instant,
    val status: TrackingStatus,
    val description: String,
    val location: String?
)

@Serializable
data class Parcel(
    val id: String,
    val trackingNumber: String,
    val displayName: String,
    val status: TrackingStatus,
    val createdAt: Instant,
    val latestEvent: ParcelEvent?,
    val carrier: Carrier? = null,
    val detectionMethod: DetectionMethod? = null,
    val metadata: Map<String, String>? = null,
    val missingMetadata: List<MetadataField>? = null,
    val lastCheckedAt: Instant? = null,
    val lastCheckError: String? = null,
    val lastCheckErrorCode: String? = null,
    val rateLimitedUntil: Instant? = null,
    val estimatedDeliveryAt: Instant? = null,
    val archivedAt: Instant? = null
)

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val sessionId: String
)

enum class UserRole {
    USER, ADMIN
}

@Serializable
data class UserAccount(
    val id: String,
    val email: String,
    val displayName: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Instant
)

@Serializable
data class DeviceSessionInfo(
    val id: String,
    val deviceName: String,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val revokedAt: Instant?
)
