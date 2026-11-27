package com.postbox.shared.repository

import com.postbox.shared.api.ParcelsRemoteDataSource
import com.postbox.shared.api.model.Parcel
import com.postbox.shared.api.model.ParcelEvent
import com.postbox.shared.cache.db.Parcel as ParcelRow
import com.postbox.shared.cache.db.Parcel_event as ParcelEventRow
import com.postbox.shared.cache.db.PostboxCache
import com.postbox.shared.cache.db.PostboxCacheQueries
import com.postbox.shared.cache.toEventRow
import com.postbox.shared.cache.toParcel
import com.postbox.shared.cache.toParcelEvent
import com.postbox.shared.cache.toRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ParcelRepository(
    private val remote: ParcelsRemoteDataSource,
    private val db: PostboxCache
) {
    suspend fun listParcels(): List<Parcel> {
        val cached = withContext(Dispatchers.Default) {
            db.postboxCacheQueries.selectParcels().executeAsList().map { it.toParcel() }
        }
        return try {
            remote.listParcels().also { persistParcels(it) }
        } catch (e: Exception) {
            if (cached.isEmpty()) throw e else cached
        }
    }

    suspend fun parcelDetail(id: String): Parcel {
        val cached = withContext(Dispatchers.Default) {
            db.postboxCacheQueries.selectParcelById(id).executeAsOneOrNull()?.toParcel()
        }
        return try {
            remote.getParcel(id).also { persistParcels(listOf(it)) }
        } catch (e: Exception) {
            cached ?: throw e
        }
    }

    suspend fun parcelEvents(id: String): List<ParcelEvent> {
        val cached = withContext(Dispatchers.Default) {
            db.postboxCacheQueries.selectEventsByParcel(id).executeAsList().map { it.toParcelEvent() }
        }
        return try {
            remote.listParcelEvents(id).also { persistEvents(id, it) }
        } catch (e: Exception) {
            if (cached.isEmpty()) throw e else cached
        }
    }

    suspend fun refreshParcel(id: String): Parcel =
        remote.refreshParcel(id).also { persistParcels(listOf(it)) }

    private suspend fun persistParcels(parcels: List<Parcel>) = withContext(Dispatchers.Default) {
        val queries = db.postboxCacheQueries
        parcels.forEach { parcel ->
            val row = parcel.toRow()
            if (queries.updateParcelRow(row).await() == 0L) queries.insertParcelRow(row).await()
            parcel.latestEvent?.let { queries.upsertEventRow(it.toEventRow(parcel.id)).await() }
        }
    }

    private suspend fun persistEvents(parcelId: String, events: List<ParcelEvent>) = withContext(Dispatchers.Default) {
        val queries = db.postboxCacheQueries
        events.forEach { queries.upsertEventRow(it.toEventRow(parcelId)).await() }
    }

    private fun PostboxCacheQueries.updateParcelRow(row: ParcelRow) = updateParcel(
        tracking_number = row.tracking_number,
        carrier = row.carrier,
        detection_method = row.detection_method,
        display_name = row.display_name,
        status = row.status,
        metadata_json = row.metadata_json,
        missing_metadata_json = row.missing_metadata_json,
        last_checked_at = row.last_checked_at,
        last_check_error = row.last_check_error,
        last_check_error_code = row.last_check_error_code,
        rate_limited_until = row.rate_limited_until,
        estimated_delivery_at = row.estimated_delivery_at,
        created_at = row.created_at,
        archived_at = row.archived_at,
        latest_event_json = row.latest_event_json,
        id = row.id
    )

    private fun PostboxCacheQueries.insertParcelRow(row: ParcelRow) = insertParcel(
        id = row.id,
        tracking_number = row.tracking_number,
        carrier = row.carrier,
        detection_method = row.detection_method,
        display_name = row.display_name,
        status = row.status,
        metadata_json = row.metadata_json,
        missing_metadata_json = row.missing_metadata_json,
        last_checked_at = row.last_checked_at,
        last_check_error = row.last_check_error,
        last_check_error_code = row.last_check_error_code,
        rate_limited_until = row.rate_limited_until,
        estimated_delivery_at = row.estimated_delivery_at,
        created_at = row.created_at,
        archived_at = row.archived_at,
        latest_event_json = row.latest_event_json
    )

    private fun PostboxCacheQueries.upsertEventRow(row: ParcelEventRow) = upsertEvent(
        id = row.id,
        parcel_id = row.parcel_id,
        occurred_at = row.occurred_at,
        status = row.status,
        description = row.description,
        location = row.location
    )
}
