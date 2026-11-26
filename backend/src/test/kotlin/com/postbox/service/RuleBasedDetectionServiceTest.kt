package com.postbox.service

import com.postbox.carrier.CarrierAdapter
import com.postbox.carrier.CarrierId
import com.postbox.carrier.CarrierRegistry
import com.postbox.carrier.FakeCarrier
import com.postbox.carrier.MetadataField
import com.postbox.carrier.TrackingResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RuleBasedDetectionServiceTest {

    private class RecordingAdapter(
        override val carrier: CarrierId,
        private val delegate: CarrierAdapter,
        val calls: MutableList<String> = mutableListOf(),
    ) : CarrierAdapter {
        override val requiredMetadata: Map<String, MetadataField> = delegate.requiredMetadata

        override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
            calls += number
            return delegate.track(number, metadata)
        }
    }

    private fun service(vararg adapters: CarrierAdapter) =
        RuleBasedDetectionService(CarrierRegistry(adapters.associateBy { it.carrier }), emptyMap())

    private val cttNumber = "DD506882333PT"
    private val dhlNumber = "1234567890"
    private val paackNumber = "534000021268935007"

    @Test
    fun `manual override always wins and is never probed`() = runTest {
        val ctt = RecordingAdapter(
            CarrierId.CTT,
            FakeCarrier(CarrierId.CTT, results = mapOf(cttNumber to FakeCarrier.deterministicResult(cttNumber))),
        )
        val result = service(ctt).detect(cttNumber, CarrierId.DHL, emptyMap())

        assertEquals(CarrierId.DHL, result.carrier)
        assertEquals(DetectionMethod.OVERRIDE, result.method)
        assertEquals(listOf(CarrierId.CTT, CarrierId.PAACK), result.candidates)
        assertTrue(ctt.calls.isEmpty(), "an overridden carrier must not be probed")
    }

    @Test
    fun `a number matching one carrier resolves in a single probe of that carrier`() = runTest {
        val dhl = RecordingAdapter(
            CarrierId.DHL,
            FakeCarrier(CarrierId.DHL, results = mapOf(dhlNumber to FakeCarrier.deterministicResult(dhlNumber))),
        )
        val paack = RecordingAdapter(CarrierId.PAACK, FakeCarrier(CarrierId.PAACK, results = emptyMap()))
        val result = service(dhl, paack).detect(dhlNumber, null, emptyMap())

        assertEquals(CarrierId.DHL, result.carrier)
        assertEquals(DetectionMethod.PROBE, result.method)
        assertEquals(listOf(CarrierId.DHL, CarrierId.PAACK), result.candidates)
        assertEquals(listOf(dhlNumber), dhl.calls)
    }

    @Test
    fun `an ambiguous number resolves to whichever carrier actually knows it`() = runTest {
        val paack = FakeCarrier(CarrierId.PAACK, results = mapOf(paackNumber to FakeCarrier.deterministicResult(paackNumber)))
        val ecoscooting = FakeCarrier(CarrierId.ECOSCOOTING, results = emptyMap())
        val result = service(paack, ecoscooting).detect(paackNumber, null, emptyMap())

        assertEquals(CarrierId.PAACK, result.carrier)
        assertEquals(DetectionMethod.PROBE, result.method)
        assertEquals(listOf(CarrierId.PAACK, CarrierId.ECOSCOOTING), result.candidates)

        val reversed = service(
            FakeCarrier(CarrierId.PAACK, results = emptyMap()),
            FakeCarrier(CarrierId.ECOSCOOTING, results = mapOf(paackNumber to FakeCarrier.deterministicResult(paackNumber))),
        ).detect(paackNumber, null, emptyMap())
        assertEquals(CarrierId.ECOSCOOTING, reversed.carrier)
        assertEquals(DetectionMethod.PROBE, reversed.method)
    }

    @Test
    fun `a candidate that needs missing metadata cannot win but does not block others`() = runTest {
        val paack = FakeCarrier(
            CarrierId.PAACK,
            requiredMetadata = mapOf("postal_code" to MetadataField("postal_code", "Postal code", "e.g. 4250-004", null)),
            results = mapOf(paackNumber to FakeCarrier.deterministicResult(paackNumber)),
        )
        val ecoscooting = FakeCarrier(CarrierId.ECOSCOOTING, results = mapOf(paackNumber to FakeCarrier.deterministicResult(paackNumber)))
        val result = service(paack, ecoscooting).detect(paackNumber, null, emptyMap())

        assertEquals(CarrierId.ECOSCOOTING, result.carrier)
        assertEquals(DetectionMethod.PROBE, result.method)
    }

    @Test
    fun `when no probe hits the top-ranked candidate is kept provisionally as a rule`() = runTest {
        val ctt = FakeCarrier(CarrierId.CTT, results = emptyMap())
        val result = service(ctt).detect(cttNumber, null, emptyMap())

        assertEquals(CarrierId.CTT, result.carrier)
        assertEquals(DetectionMethod.RULE, result.method)
        assertEquals(listOf(CarrierId.CTT, CarrierId.PAACK), result.candidates)
    }

    @Test
    fun `a number with no rule candidates resolves to null carrier`() = runTest {
        val result = service().detect("A1", null, emptyMap())

        assertEquals(null, result.carrier)
        assertEquals(DetectionMethod.RULE, result.method)
        assertEquals(emptyList(), result.candidates)
    }
}
