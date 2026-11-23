package com.postbox.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParcelRepositoryTest {

    @Test
    fun `parcel and event CRUD round-trip preserves all columns`() {
        val db = freshTestDatabase("parcel-crud")
        val users = UserRepository(db)
        val parcels = ParcelRepository(db)
        val user = users.create("alice@example.com", "hash-123", displayName = "Alice")

        val created = parcels.createParcel(
            userId = user.id,
            trackingNumber = "1Z999AA10123456784",
            displayName = "Shoes",
            status = "IN_TRANSIT",
            carrier = "UPS",
            carrierMetadata = """{"service":"ground"}""",
            lastCheckedAt = "2026-11-22T08:00:00.000Z",
        )
        assertEquals(created, parcels.findParcelForUser(user.id, created.id))
        assertEquals(listOf(created), parcels.listParcelsForUser(user.id))

        val event = parcels.addEventForUser(
            userId = user.id,
            parcelId = created.id,
            occurredAt = "2026-11-20T10:00:00.000Z",
            status = "IN_TRANSIT",
            description = "Arrived at hub",
            location = "Berlin",
            raw = """{"carrier":"raw-payload"}""",
        )
        assertNotNull(event)
        val events = parcels.listEventsForUser(user.id, created.id)
        assertEquals(1, events.size)
        assertEquals("Arrived at hub", events[0].description)
        assertEquals("Berlin", events[0].location)

        // raw is stored in the database row but never exposed by the read model
        val rawInDb = dbQuery(db) {
            ParcelEvents.selectAll().where { ParcelEvents.id eq event.id }.first()[ParcelEvents.raw]
        }
        assertEquals("""{"carrier":"raw-payload"}""", rawInDb)

        // own update and delete affect exactly one row
        assertEquals(
            1,
            parcels.updateParcelForUser(
                user.id, created.id, "Shoes EU", "DELIVERED", "UPS", null, "2026-11-23T09:00:00.000Z", "2026-11-23T09:30:00.000Z",
            ),
        )
        val updated = parcels.findParcelForUser(user.id, created.id)
        assertEquals("DELIVERED", updated?.status)
        assertEquals("Shoes EU", updated?.displayName)
        assertEquals(null, updated?.carrierMetadata)
        assertEquals("2026-11-23T09:30:00.000Z", updated?.archivedAt)
        assertEquals(1, parcels.deleteParcelForUser(user.id, created.id))
        assertNull(parcels.findParcelForUser(user.id, created.id))
    }

    @Test
    fun `cross-user reads return empty and writes affect zero rows`() {
        val db = freshTestDatabase("parcel-scoping")
        val users = UserRepository(db)
        val parcels = ParcelRepository(db)
        val alice = users.create("alice@example.com", "hash-a", displayName = "Alice")
        val bob = users.create("bob@example.com", "hash-b", displayName = "Bob")
        val parcel = parcels.createParcel(alice.id, "TRACK-A", "A parcel", "IN_TRANSIT")
        parcels.addEventForUser(alice.id, parcel.id, "2026-11-20T10:00:00.000Z", "IN_TRANSIT", "scan", null, "raw-a")

        assertEquals(emptyList(), parcels.listParcelsForUser(bob.id))
        assertNull(parcels.findParcelForUser(bob.id, parcel.id))
        assertEquals(emptyList(), parcels.listEventsForUser(bob.id, parcel.id))
        assertNull(
            parcels.addEventForUser(bob.id, parcel.id, "2026-11-21T10:00:00.000Z", "OUT_FOR_DELIVERY", "fake", null, "raw-b"),
        )
        assertEquals(0, parcels.updateParcelForUser(bob.id, parcel.id, "Hacked", "DELIVERED", null, null, null, null))
        assertEquals(0, parcels.deleteParcelForUser(bob.id, parcel.id))

        val reloaded = parcels.findParcelForUser(alice.id, parcel.id)
        assertEquals("A parcel", reloaded?.displayName)
        assertEquals("IN_TRANSIT", reloaded?.status)
        assertEquals(1, parcels.listEventsForUser(alice.id, parcel.id).size)
    }

    @Test
    fun `duplicate event insert is idempotent via the unique index`() {
        val db = freshTestDatabase("parcel-event-idempotent")
        val users = UserRepository(db)
        val parcels = ParcelRepository(db)
        val user = users.create("alice@example.com", "hash-123", displayName = "Alice")
        val parcel = parcels.createParcel(user.id, "TRACK-A", "A parcel", "IN_TRANSIT")

        val first = parcels.addEventForUser(user.id, parcel.id, "2026-11-20T10:00:00.000Z", "IN_TRANSIT", "scan", null, "raw-1")
        val second = parcels.addEventForUser(user.id, parcel.id, "2026-11-20T10:00:00.000Z", "IN_TRANSIT", "scan", null, "raw-2")

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.id, second.id)
        assertEquals(1, parcels.listEventsForUser(user.id, parcel.id).size)
    }
}
