package com.postbox.shared.repository

import app.cash.sqldelight.coroutines.asFlow
import com.postbox.shared.api.ParcelsRemoteDataSource
import com.postbox.shared.cache.db.Parcel as ParcelRow
import com.postbox.shared.cache.db.ParcelEvent as ParcelEventRow
import com.postbox.shared.cache.db.PostboxCache
import com.postbox.shared.cache.db.PostboxCacheQueries
import com.postbox.shared.cache.toEventRow
import com.postbox.shared.cache.toParcel
import com.postbox.shared.cache.toParcelEvent
import com.postbox.shared.cache.toRow
import com.postbox.shared.domain.model.Parcel
import com.postbox.shared.domain.model.ParcelEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ParcelRepository(
    private val remote: ParcelsRemoteDataSource,
    private val db: PostboxCache,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    fun parcelsFlow(): Flow<List<Parcel>> =
        db.postboxCacheQueries.selectParcels().asFlow()
            .map { query -> query.executeAsList().map { it.toParcel() } }

    fun parcelEventsFlow(parcelId: String): Flow<List<ParcelEvent>> =
        db.postboxCacheQueries.selectEventsByParcel(parcelId).asFlow()
            .map { query -> query.executeAsList().map { it.toParcelEvent() } }

    suspend fun listParcels(): List<Parcel> {
        val cached = withContext(ioDispatcher) {
            db.postboxCacheQueries.selectParcels().executeAsList().map { it.toParcel() }
        }
        return try {
            remote.listParcels().also { persistParcels(it) }
        } catch (e: Exception) {
            if (cached.isEmpty()) throw e else cached
        }
    }

    suspend fun parcelDetail(id: String): Parcel {
        val cached = withContext(ioDispatcher) {
            db.postboxCacheQueries.selectParcelById(id).executeAsOneOrNull()?.toParcel()
        }
        return try {
            remote.getParcel(id).also { persistParcels(listOf(it)) }
        } catch (e: Exception) {
            cached ?: throw e
        }
    }

    suspend fun parcelEvents(id: String): List<ParcelEvent> {
        val cached = withContext(ioDispatcher) {
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

    suspend fun clearCache() = withContext(ioDispatcher) {
        db.postboxCacheQueries.clearEvents()
        db.postboxCacheQueries.clearParcels()
    }

    private suspend fun persistParcels(parcels: List<Parcel>) = withContext(ioDispatcher) {
        val queries = db.postboxCacheQueries
        parcels.forEach { parcel ->
            queries.upsertParcelRow(parcel.toRow()).await()
            parcel.latestEvent?.let { queries.upsertEventRow(it.toEventRow(parcel.id)).await() }
        }
    }

    private suspend fun persistEvents(parcelId: String, events: List<ParcelEvent>) = withContext(ioDispatcher) {
        val queries = db.postboxCacheQueries
        events.forEach { queries.upsertEventRow(it.toEventRow(parcelId)).await() }
    }

    private fun PostboxCacheQueries.upsertParcelRow(row: ParcelRow) = upsertParcel(
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
