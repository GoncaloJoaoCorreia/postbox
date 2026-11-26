package com.postbox.service

import com.postbox.carrier.CarrierId
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingNumberRulesTest {

    @Test
    fun `DHL Express 10-digit sample ranks dhl first`() {
        assertEquals(listOf(CarrierId.DHL, CarrierId.PAACK), TrackingNumberRules.candidatesFor("1234567890"))
    }

    @Test
    fun `DHL eCommerce GM sample ranks dhl first`() {
        assertEquals(listOf(CarrierId.DHL, CarrierId.PAACK), TrackingNumberRules.candidatesFor("GM1234567890123456"))
    }

    @Test
    fun `DHL Parcel International JVGL sample is a low-confidence dhl hint`() {
        assertEquals(listOf(CarrierId.DHL, CarrierId.PAACK), TrackingNumberRules.candidatesFor("JVGL123456789012345"))
    }

    @Test
    fun `CTT S10 sample ending in PT with valid check digit ranks ctt first, case-insensitive`() {
        assertEquals(listOf(CarrierId.CTT, CarrierId.PAACK), TrackingNumberRules.candidatesFor("DD506882333PT"))
        assertEquals(listOf(CarrierId.CTT, CarrierId.PAACK), TrackingNumberRules.candidatesFor("dd506882333pt"))
    }

    @Test
    fun `S10 format alone is not enough - wrong check digit or non-PT suffix drops the ctt candidate`() {
        assertEquals(listOf(CarrierId.PAACK), TrackingNumberRules.candidatesFor("RR000000000PT"))
        assertEquals(listOf(CarrierId.PAACK), TrackingNumberRules.candidatesFor("DD506882333NL"))
    }

    @Test
    fun `CTT numeric 22 and 25 digit samples rank ctt first`() {
        assertEquals(listOf(CarrierId.CTT, CarrierId.PAACK), TrackingNumberRules.candidatesFor("0010000082809458634407"))
        assertEquals(listOf(CarrierId.CTT, CarrierId.PAACK), TrackingNumberRules.candidatesFor("0010000082809458634407123"))
    }

    @Test
    fun `Paack 18-digit sample ranks paack above the weaker ecoscooting numeric rule`() {
        assertEquals(listOf(CarrierId.PAACK, CarrierId.ECOSCOOTING), TrackingNumberRules.candidatesFor("534000021268935007"))
    }

    @Test
    fun `EcoScooting samples rank ecoscooting first`() {
        assertEquals(listOf(CarrierId.ECOSCOOTING, CarrierId.PAACK), TrackingNumberRules.candidatesFor("CNPRT42500041234024382386"))
        assertEquals(listOf(CarrierId.ECOSCOOTING, CarrierId.PAACK), TrackingNumberRules.candidatesFor("460357243181"))
    }

    @Test
    fun `a number matching no v1 rule yields no candidates`() {
        assertEquals(emptyList(), TrackingNumberRules.candidatesFor("A1"))
    }
}
