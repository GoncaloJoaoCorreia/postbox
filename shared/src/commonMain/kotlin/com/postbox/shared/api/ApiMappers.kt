package com.postbox.shared.api

import com.postbox.shared.domain.model.Carrier
import com.postbox.shared.domain.model.DetectionMethod
import com.postbox.shared.domain.model.MetadataField
import com.postbox.shared.domain.model.Parcel
import com.postbox.shared.domain.model.ParcelEvent
import com.postbox.shared.domain.model.Session
import com.postbox.shared.domain.model.TrackingStatus
import com.postbox.shared.api.model.Carrier as ApiCarrier
import com.postbox.shared.api.model.DetectionMethod as ApiDetectionMethod
import com.postbox.shared.api.model.LoginResponse
import com.postbox.shared.api.model.MetadataField as ApiMetadataField
import com.postbox.shared.api.model.Parcel as ApiParcel
import com.postbox.shared.api.model.ParcelEvent as ApiParcelEvent
import com.postbox.shared.api.model.TrackingStatus as ApiTrackingStatus

fun ApiTrackingStatus.toDomain(): TrackingStatus = TrackingStatus.valueOf(name)

fun ApiCarrier.toDomain(): Carrier = Carrier.valueOf(name)

fun ApiDetectionMethod.toDomain(): DetectionMethod = DetectionMethod.valueOf(name)

fun ApiMetadataField.toDomain(): MetadataField = MetadataField(
    name = name,
    label = label,
    hint = hint,
    validationRegex = validationRegex
)

fun ApiParcelEvent.toDomain(): ParcelEvent = ParcelEvent(
    id = id,
    occurredAt = occurredAt,
    status = status.toDomain(),
    description = description,
    location = location
)

fun ApiParcel.toDomain(): Parcel = Parcel(
    id = id,
    trackingNumber = trackingNumber,
    displayName = displayName,
    status = status.toDomain(),
    createdAt = createdAt,
    latestEvent = latestEvent?.toDomain(),
    carrier = carrier?.toDomain(),
    detectionMethod = detectionMethod?.toDomain(),
    metadata = metadata,
    missingMetadata = missingMetadata?.map { it.toDomain() },
    lastCheckedAt = lastCheckedAt,
    lastCheckError = lastCheckError,
    lastCheckErrorCode = lastCheckErrorCode,
    rateLimitedUntil = rateLimitedUntil,
    estimatedDeliveryAt = estimatedDeliveryAt,
    archivedAt = archivedAt
)

fun LoginResponse.toSession(): Session = Session(
    accessToken = token,
    refreshToken = refreshToken,
    sessionId = sessionId
)
