package com.postbox.carrier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CarrierIdTest {

    @Test
    fun `ids are the pinned lowercase persistence contract`() {
        assertEquals(
            mapOf(
                CarrierId.DHL to "dhl",
                CarrierId.CTT to "ctt",
                CarrierId.POSTNL to "postnl",
                CarrierId.DPD to "dpd",
                CarrierId.PAACK to "paack",
                CarrierId.ECOSCOOTING to "ecoscooting",
            ),
            CarrierId.entries.associateWith { it.id },
        )
    }

    @Test
    fun `fromId round-trips every stored id and returns null for unknown`() {
        for (carrier in CarrierId.entries) {
            assertEquals(carrier, CarrierId.fromId(carrier.id))
        }
        assertNull(CarrierId.fromId("ups"))
    }
}
