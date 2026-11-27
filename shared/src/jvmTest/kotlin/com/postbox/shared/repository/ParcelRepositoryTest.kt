package com.postbox.shared.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.postbox.shared.api.ApiException
import com.postbox.shared.api.ParcelsRemoteDataSource
import com.postbox.shared.cache.db.PostboxCache
import com.postbox.shared.domain.model.Carrier
import com.postbox.shared.domain.model.Parcel
import com.postbox.shared.domain.model.ParcelEvent
import com.postbox.shared.domain.model.TrackingStatus
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class FakeRemote(
    var parcels: List<Parcel> = emptyList(),
    var failure: Exception? = null
) : ParcelsRemoteDataSource {
    override suspend fun listParcels(): List<Parcel> = failure?.let { throw it } ?: parcels

    override suspend fun getParcel(id: String): Parcel =
        failure?.let { throw it } ?: parcels.first { it.id == id }

    override suspend fun listParcelEvents(id: String): List<ParcelEvent> =
        failure?.let { throw it } ?: emptyList()

    override suspend fun refreshParcel(id: String): Parcel =
        failure?.let { throw it } ?: parcels.first { it.id == id }
}

private fun parcel(id: String) = Parcel(
    id = id,
    trackingNumber = "1Z999",
    displayName = "1Z999",
    status = TrackingStatus.IN_TRANSIT,
    createdAt = Instant.parse("2026-11-27T10:00:00Z"),
    latestEvent = ParcelEvent(
        id = "e-$id",
        occurredAt = Instant.parse("2026-11-27T11:00:00Z"),
        status = TrackingStatus.IN_TRANSIT,
        description = "In transit",
        location = null
    ),
    carrier = Carrier.dhl
)

class ParcelRepositoryTest {
    private fun newDb(): PostboxCache {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PostboxCache.Schema.create(driver)
        return PostboxCache(driver)
    }

    @Test
    fun networkSyncPopulatesCacheAndCacheServesWhenOffline() = runTest {
        val remote = FakeRemote(parcels = listOf(parcel("p1")))
        val db = newDb()
        val repo = ParcelRepository(remote, db, StandardTestDispatcher(testScheduler))

        assertEquals("p1", repo.listParcels().single().id)

        remote.failure = IOException("offline")
        assertEquals("p1", repo.listParcels().single().id)
        assertEquals(1, db.postboxCacheQueries.selectAllParcels().executeAsList().size)
        assertEquals(1, db.postboxCacheQueries.selectEventsByParcel("p1").executeAsList().size)
    }

    @Test
    fun detailFallsBackToCacheOnFailure() = runTest {
        val remote = FakeRemote(parcels = listOf(parcel("p1")))
        val repo = ParcelRepository(remote, newDb(), StandardTestDispatcher(testScheduler))
        repo.listParcels()

        remote.failure = IOException("offline")
        assertEquals("p1", repo.parcelDetail("p1").id)
    }

    @Test
    fun apiErrorSurfacesWhenCacheIsEmpty() = runTest {
        val remote = FakeRemote(failure = ApiException("unauthorized", "A valid bearer token is required", 401))
        val repo = ParcelRepository(remote, newDb(), StandardTestDispatcher(testScheduler))
        val e = assertFailsWith<ApiException> { repo.listParcels() }
        assertEquals("unauthorized", e.code)
        assertEquals(401, e.status)
    }

    @Test
    fun parcelsFlowEmitsCachedParcels() = runTest {
        val remote = FakeRemote(parcels = listOf(parcel("p1")))
        val repo = ParcelRepository(remote, newDb(), StandardTestDispatcher(testScheduler))
        repo.listParcels()

        assertEquals("p1", repo.parcelsFlow().first().single().id)
    }
}
