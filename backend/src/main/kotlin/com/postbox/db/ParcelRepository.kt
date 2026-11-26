package com.postbox.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** Read model for a parcel row. `status` is plain TEXT end to end (AC-003). */
data class ParcelRecord(
    val id: String,
    val userId: String,
    val trackingNumber: String,
    val carrier: String?,
    val detectionMethod: String?,
    val displayName: String,
    val status: String,
    val carrierMetadata: String?,
    val lastCheckedAt: String?,
    val lastCheckError: String?,
    val estimatedDeliveryAt: String?,
    val createdAt: String,
    val archivedAt: String?,
)

/**
 * Read model for a parcel event. The raw carrier payload stays in the `raw`
 * column for debugging and is never part of a read model (AC-001).
 */
data class ParcelEventRecord(
    val id: String,
    val parcelId: String,
    val occurredAt: String,
    val status: String,
    val description: String,
    val location: String?,
)

/**
 * Parcel and event persistence. Every query is scoped by `user_id` — directly on
 * `parcels`, and through parcel ownership on `parcel_events` (which has no
 * `user_id` column) — so cross-user reads return empty and cross-user writes
 * affect zero rows (AC-001).
 */
class ParcelRepository(private val db: Database) {

    fun createParcel(
        userId: String,
        trackingNumber: String,
        displayName: String,
        status: String,
        carrier: String? = null,
        detectionMethod: String? = null,
        carrierMetadata: String? = null,
        lastCheckedAt: String? = null,
        estimatedDeliveryAt: String? = null,
    ): ParcelRecord = dbQuery(db) {
        val id = UUID.randomUUID().toString()
        val createdAt = nowIso()
        Parcels.insert {
            it[Parcels.id] = id
            it[Parcels.userId] = userId
            it[Parcels.trackingNumber] = trackingNumber
            it[Parcels.carrier] = carrier
            it[Parcels.detectionMethod] = detectionMethod
            it[Parcels.displayName] = displayName
            it[Parcels.status] = status
            it[Parcels.carrierMetadata] = carrierMetadata
            it[Parcels.lastCheckedAt] = lastCheckedAt
            it[Parcels.estimatedDeliveryAt] = estimatedDeliveryAt
            it[Parcels.createdAt] = createdAt
        }
        ParcelRecord(
            id = id,
            userId = userId,
            trackingNumber = trackingNumber,
            carrier = carrier,
            detectionMethod = detectionMethod,
            displayName = displayName,
            status = status,
            carrierMetadata = carrierMetadata,
            lastCheckedAt = lastCheckedAt,
            lastCheckError = null,
            estimatedDeliveryAt = estimatedDeliveryAt,
            createdAt = createdAt,
            archivedAt = null,
        )
    }

    /** Null when the parcel does not exist or belongs to another user. */
    fun findParcelForUser(userId: String, parcelId: String): ParcelRecord? = dbQuery(db) {
        Parcels.selectAll()
            .where { (Parcels.id eq parcelId) and (Parcels.userId eq userId) }
            .firstOrNull()?.let(::toParcelRecord)
    }

    fun listParcelsForUser(userId: String): List<ParcelRecord> = dbQuery(db) {
        Parcels.selectAll().where { Parcels.userId eq userId }.map(::toParcelRecord)
    }

    /** Returns affected rows — zero when the parcel belongs to another user. */
    fun updateParcelForUser(
        userId: String,
        parcelId: String,
        displayName: String,
        status: String,
        carrier: String?,
        detectionMethod: String?,
        carrierMetadata: String?,
        lastCheckedAt: String?,
        archivedAt: String?,
        lastCheckError: String?,
        estimatedDeliveryAt: String?,
    ): Int = dbQuery(db) {
        Parcels.update({ (Parcels.id eq parcelId) and (Parcels.userId eq userId) }) {
            it[Parcels.displayName] = displayName
            it[Parcels.status] = status
            it[Parcels.carrier] = carrier
            it[Parcels.detectionMethod] = detectionMethod
            it[Parcels.carrierMetadata] = carrierMetadata
            it[Parcels.lastCheckedAt] = lastCheckedAt
            it[Parcels.archivedAt] = archivedAt
            it[Parcels.lastCheckError] = lastCheckError
            it[Parcels.estimatedDeliveryAt] = estimatedDeliveryAt
        }
    }

    /**
     * Deletes the parcel and its events in one transaction; returns affected
     * parcel rows — zero when the parcel belongs to another user. The schema
     * declares `ON DELETE CASCADE` and connections run with
     * `PRAGMA foreign_keys = ON`, and the explicit event delete (scoped to an
     * actually deleted parcel) keeps the invariant even without the pragma.
     */
    fun deleteParcelForUser(userId: String, parcelId: String): Int = dbQuery(db) {
        val deleted = Parcels.deleteWhere { (Parcels.id eq parcelId) and (Parcels.userId eq userId) }
        if (deleted > 0) {
            ParcelEvents.deleteWhere { ParcelEvents.parcelId eq parcelId }
        }
        deleted
    }

    /**
     * Adds an event to a parcel owned by [userId]; returns null for another user's
     * parcel. A duplicate (same parcel_id/occurred_at/status/description) is swallowed
     * by the unique idempotency index, leaving exactly one row. [raw] is stored in
     * the `raw` column but never returned.
     */
    fun addEventForUser(
        userId: String,
        parcelId: String,
        occurredAt: String,
        status: String,
        description: String,
        location: String?,
        raw: String,
    ): ParcelEventRecord? = dbQuery(db) {
        val owned = Parcels.selectAll()
            .where { (Parcels.id eq parcelId) and (Parcels.userId eq userId) }
            .any()
        if (!owned) return@dbQuery null
        ParcelEvents.insertIgnore {
            it[ParcelEvents.id] = UUID.randomUUID().toString()
            it[ParcelEvents.parcelId] = parcelId
            it[ParcelEvents.occurredAt] = occurredAt
            it[ParcelEvents.status] = status
            it[ParcelEvents.description] = description
            it[ParcelEvents.location] = location
            it[ParcelEvents.raw] = raw
        }
        ParcelEvents.selectAll()
            .where {
                (ParcelEvents.parcelId eq parcelId) and
                    (ParcelEvents.occurredAt eq occurredAt) and
                    (ParcelEvents.status eq status) and
                    (ParcelEvents.description eq description)
            }
            .first().let(::toEventRecord)
    }

    /** Newest event of a parcel owned by [userId]; null when the parcel is not owned or has no events. */
    fun findLatestEventForUser(userId: String, parcelId: String): ParcelEventRecord? = dbQuery(db) {
        val owned = Parcels.selectAll()
            .where { (Parcels.id eq parcelId) and (Parcels.userId eq userId) }
            .any()
        if (!owned) return@dbQuery null
        ParcelEvents.selectAll()
            .where { ParcelEvents.parcelId eq parcelId }
            .orderBy(ParcelEvents.occurredAt, SortOrder.DESC)
            .firstOrNull()
            ?.let(::toEventRecord)
    }

    /** Events of a parcel owned by [userId]; empty for another user's parcel. */
    fun listEventsForUser(userId: String, parcelId: String): List<ParcelEventRecord> = dbQuery(db) {
        val owned = Parcels.selectAll()
            .where { (Parcels.id eq parcelId) and (Parcels.userId eq userId) }
            .any()
        if (!owned) return@dbQuery emptyList()
        ParcelEvents.selectAll()
            .where { ParcelEvents.parcelId eq parcelId }
            .orderBy(ParcelEvents.occurredAt)
            .map(::toEventRecord)
    }

    private fun toParcelRecord(row: ResultRow) = ParcelRecord(
        id = row[Parcels.id],
        userId = row[Parcels.userId],
        trackingNumber = row[Parcels.trackingNumber],
        carrier = row[Parcels.carrier],
        detectionMethod = row[Parcels.detectionMethod],
        displayName = row[Parcels.displayName],
        status = row[Parcels.status],
        carrierMetadata = row[Parcels.carrierMetadata],
        lastCheckedAt = row[Parcels.lastCheckedAt],
        lastCheckError = row[Parcels.lastCheckError],
        estimatedDeliveryAt = row[Parcels.estimatedDeliveryAt],
        createdAt = row[Parcels.createdAt],
        archivedAt = row[Parcels.archivedAt],
    )

    private fun toEventRecord(row: ResultRow) = ParcelEventRecord(
        id = row[ParcelEvents.id],
        parcelId = row[ParcelEvents.parcelId],
        occurredAt = row[ParcelEvents.occurredAt],
        status = row[ParcelEvents.status],
        description = row[ParcelEvents.description],
        location = row[ParcelEvents.location],
    )
}
