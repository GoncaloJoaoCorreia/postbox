package com.postbox.carrier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CarrierRegistryTest {

    private class StubAdapter(override val carrier: CarrierId) : CarrierAdapter {
        override val requiredMetadata: Map<String, MetadataField> = emptyMap()

        override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult =
            throw CarrierError.NotFound(number)
    }

    @Test
    fun `lookup returns the registered adapter and null for an unregistered carrier`() {
        val dhl = StubAdapter(CarrierId.DHL)
        val registry = CarrierRegistry(mapOf(CarrierId.DHL to dhl))

        assertSame(dhl, registry.lookup(CarrierId.DHL))
        assertNull(registry.lookup(CarrierId.CTT))
    }

    @Test
    fun `all yields exactly the registered adapters, and a new map entry needs no other change`() {
        val dhl = StubAdapter(CarrierId.DHL)
        val ctt = StubAdapter(CarrierId.CTT)
        val registry = CarrierRegistry(mapOf(CarrierId.DHL to dhl, CarrierId.CTT to ctt))
        assertEquals(listOf(dhl, ctt), registry.all)

        val postnl = StubAdapter(CarrierId.POSTNL)
        val withThird = CarrierRegistry(
            mapOf(
                CarrierId.DHL to dhl,
                CarrierId.CTT to ctt,
                CarrierId.POSTNL to postnl,
            )
        )
        assertEquals(listOf(dhl, ctt, postnl), withThird.all)
    }
}
