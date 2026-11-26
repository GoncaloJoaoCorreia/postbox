package com.postbox.service

import com.postbox.db.ParcelRecord

/**
 * Refreshes one parcel from its carrier: new events inserted, status
 * recalculated, `last_checked_at` updated, and the updated parcel returned.
 * The API layer maps [ServiceNotImplemented] from the wired default to
 * `501 not_implemented`.
 */
interface RefreshService {
    suspend fun refresh(userId: String, parcelId: String): ParcelRecord
}

object NotImplementedRefreshService : RefreshService {
    override suspend fun refresh(userId: String, parcelId: String): ParcelRecord =
        throw ServiceNotImplemented("POST /api/v1/parcels/{id}/refresh")
}
