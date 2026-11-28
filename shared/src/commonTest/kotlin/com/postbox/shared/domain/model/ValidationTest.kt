package com.postbox.shared.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidationTest {
    @Test
    fun trackingNumberTrimsAndAcceptsAlphanumericsWithHyphens() {
        assertEquals("1Z999AA1", normalizeTrackingNumber("  1Z999AA1 "))
        assertEquals("a", normalizeTrackingNumber("a"))
        assertEquals(64, normalizeTrackingNumber("x".repeat(64))?.length)
    }

    @Test
    fun trackingNumberRejectsEmptyTooLongAndBadCharset() {
        assertNull(normalizeTrackingNumber("   "))
        assertNull(normalizeTrackingNumber("x".repeat(65)))
        assertNull(normalizeTrackingNumber("-abc"))
        assertNull(normalizeTrackingNumber("has space"))
        assertNull(normalizeTrackingNumber("under_score"))
    }

    @Test
    fun displayNameMustBeNonBlankAndCappedAt120() {
        assertTrue(isValidDisplayName("My parcel"))
        assertTrue(isValidDisplayName("x".repeat(120)))
        assertFalse(isValidDisplayName("   "))
        assertFalse(isValidDisplayName("x".repeat(121)))
    }

    @Test
    fun metadataFieldValidatesAgainstItsRegex() {
        val postcode = MetadataField("postcode", "Delivery postcode", "hint", "^[0-9A-Za-z][0-9A-Za-z -]{2,9}$")
        assertTrue(postcode.accepts("4250-004"))
        assertFalse(postcode.accepts("42"))
        assertFalse(postcode.accepts(""))
        assertFalse(postcode.accepts("x".repeat(11)))
        val withoutRegex = MetadataField("country", "Destination country", "hint", null)
        assertTrue(withoutRegex.accepts("anything"))
    }

    @Test
    fun carrierMetadataCatalogMatchesServerRequiredFields() {
        val paackPostcode = CarrierMetadata.requiredFieldsFor(Carrier.paack).single()
        assertEquals("postcode", paackPostcode.name)
        assertTrue(paackPostcode.accepts("4250-004"))
        assertFalse(paackPostcode.accepts("no"))

        val postnlPostcode = CarrierMetadata.requiredFieldsFor(Carrier.postnl).single()
        assertEquals("postcode", postnlPostcode.name)
        assertTrue(postnlPostcode.accepts("1234AB"))
        assertTrue(postnlPostcode.accepts("4250-004"))
        assertFalse(postnlPostcode.accepts("ABCDE"))

        val dpdCountry = CarrierMetadata.requiredFieldsFor(Carrier.dpd).single()
        assertEquals("country", dpdCountry.name)
        assertTrue(dpdCountry.accepts("PT"))
        assertTrue(dpdCountry.accepts("NL"))
        assertFalse(dpdCountry.accepts("DE"))

        assertTrue(CarrierMetadata.requiredFieldsFor(Carrier.dhl).isEmpty())
        assertTrue(CarrierMetadata.requiredFieldsFor(Carrier.ctt).isEmpty())
        assertTrue(CarrierMetadata.requiredFieldsFor(Carrier.ecoscooting).isEmpty())
    }
}
