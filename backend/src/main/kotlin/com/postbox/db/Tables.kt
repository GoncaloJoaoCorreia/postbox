package com.postbox.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table objects matching the V1 SQL migration one-to-one.
 * All ids are UUID text and all timestamps are ISO-8601 UTC text;
 * status columns are TEXT (parent AC-003).
 */
object Users : Table("users") {
    val id = text("id")
    val email = text("email").uniqueIndex()
    val passwordHash = text("password_hash")
    val displayName = text("display_name")
    val role = text("role").default("USER")
    val isActive = bool("is_active").default(true)
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Parcels : Table("parcels") {
    val id = text("id")
    val userId = text("user_id").references(Users.id)
    val trackingNumber = text("tracking_number")
    val carrier = text("carrier").nullable()
    val detectionMethod = text("detection_method").nullable()
    val displayName = text("display_name")
    val status = text("status")
    val carrierMetadata = text("carrier_metadata").nullable()
    val lastCheckedAt = text("last_checked_at").nullable()
    val lastCheckError = text("last_check_error").nullable()
    val createdAt = text("created_at")
    val archivedAt = text("archived_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(customIndexName = "idx_parcels_user_id", isUnique = false, userId)
    }
}

object ParcelEvents : Table("parcel_events") {
    val id = text("id")
    val parcelId = text("parcel_id").references(Parcels.id)
    val occurredAt = text("occurred_at")
    val status = text("status")
    val description = text("description")
    val location = text("location").nullable()
    val raw = text("raw")

    override val primaryKey = PrimaryKey(id)

    init {
        index(customIndexName = "idx_parcel_events_parcel_id", isUnique = false, parcelId)
        uniqueIndex(
            customIndexName = "uq_parcel_events_idempotency",
            parcelId, occurredAt, status, description,
        )
    }
}
