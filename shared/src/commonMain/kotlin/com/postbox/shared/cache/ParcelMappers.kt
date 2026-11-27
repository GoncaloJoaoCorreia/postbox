package com.postbox.shared.cache

import com.postbox.shared.cache.db.Parcel as ParcelRow
import com.postbox.shared.cache.db.ParcelEvent as ParcelEventRow
import com.postbox.shared.domain.model.Carrier
import com.postbox.shared.domain.model.DetectionMethod
import com.postbox.shared.domain.model.MetadataField
import com.postbox.shared.domain.model.Parcel
import com.postbox.shared.domain.model.ParcelEvent
import com.postbox.shared.domain.model.TrackingStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun ParcelRow.toParcel(): Parcel = Parcel(
    id = id,
    trackingNumber = tracking_number,
    displayName = display_name,
    status = TrackingStatus.valueOf(status),
    createdAt = Instant.parse(created_at),
    latestEvent = latest_event_json?.let { json.decodeFromString<ParcelEvent>(it) },
    carrier = carrier?.let { Carrier.valueOf(it) },
    detectionMethod = detection_method?.let { DetectionMethod.valueOf(it) },
    metadata = metadata_json?.let { json.decodeFromString<Map<String, String>>(it) },
    missingMetadata = missing_metadata_json?.let { json.decodeFromString<List<MetadataField>>(it) },
    lastCheckedAt = last_checked_at?.let(Instant::parse),
    lastCheckError = last_check_error,
    lastCheckErrorCode = last_check_error_code,
    rateLimitedUntil = rate_limited_until?.let(Instant::parse),
    estimatedDeliveryAt = estimated_delivery_at?.let(Instant::parse),
    archivedAt = archived_at?.let(Instant::parse)
)

fun Parcel.toRow(): ParcelRow = ParcelRow(
    id = id,
    tracking_number = trackingNumber,
    carrier = carrier?.name,
    detection_method = detectionMethod?.name,
    display_name = displayName,
    status = status.name,
    metadata_json = metadata?.let { json.encodeToString(it) },
    missing_metadata_json = missingMetadata?.let { json.encodeToString(it) },
    last_checked_at = lastCheckedAt?.toString(),
    last_check_error = lastCheckError,
    last_check_error_code = lastCheckErrorCode,
    rate_limited_until = rateLimitedUntil?.toString(),
    estimated_delivery_at = estimatedDeliveryAt?.toString(),
    created_at = createdAt.toString(),
    archived_at = archivedAt?.toString(),
    latest_event_json = latestEvent?.let { json.encodeToString(it) }
)

fun ParcelEventRow.toParcelEvent(): ParcelEvent = ParcelEvent(
    id = id,
    occurredAt = Instant.parse(occurred_at),
    status = TrackingStatus.valueOf(status),
    description = description,
    location = location
)

fun ParcelEvent.toEventRow(parcelId: String): ParcelEventRow = ParcelEventRow(
    id = id,
    parcel_id = parcelId,
    occurred_at = occurredAt.toString(),
    status = status.name,
    description = description,
    location = location
)
