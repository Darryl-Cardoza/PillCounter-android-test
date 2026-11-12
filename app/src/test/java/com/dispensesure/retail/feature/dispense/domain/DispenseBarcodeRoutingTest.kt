package com.dispensesure.retail.feature.dispense.domain

import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.utils.common.BarcodeDecoder
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RAW_RX_LABEL = "RX999|NDC123|10|B1"
private const val GS1_CONTAINER_LABEL = "]C1010036854010001417251231" + "10LOT123"
private const val PLAIN_UPC = "368540100014"
private const val SCAN_IMAGE_PATH = "/scans/barcode.jpg"

/** Unit tests for [DispenseBarcodeRouting]. Real [ScanDecoder], no Android. */
class DispenseBarcodeRoutingTest {

    private val routing = DispenseBarcodeRouting(ScanDecoder(BarcodeDecoder()), Region.US)

    private fun route(
        rawValue: String,
        stage: DispenseStage,
        currentStep: StepState = StepState.TARGET_VERIFICATION,
        isVialStillShown: Boolean = false,
    ) = routing.route(rawValue, SCAN_IMAGE_PATH, stage, currentStep, isVialStillShown)

    // ─────────────────────────── route ───────────────────────────

    @Test
    fun `a blank read is ignored on every stage`() {
        DispenseStage.entries.forEach { stage ->
            assertEquals(BarcodeRoute.Ignored, route("   ", stage))
        }
    }

    @Test
    fun `the queue and the Rx step read Rx labels as they are`() {
        assertEquals(BarcodeRoute.RxLabel(RAW_RX_LABEL), route(RAW_RX_LABEL, DispenseStage.QUEUE))
        assertEquals(BarcodeRoute.RxLabel(RAW_RX_LABEL), route(RAW_RX_LABEL, DispenseStage.PRE_RX))
    }

    @Test
    fun `an Rx label on the container step is flagged`() {
        assertEquals(BarcodeRoute.RxLabelOnContainerStep, route(RAW_RX_LABEL, DispenseStage.PRE_NDC))
    }

    @Test
    fun `a GS1 container label carries its lot and expiry as the first bottle`() {
        val containerRoute = route(GS1_CONTAINER_LABEL, DispenseStage.PRE_NDC) as BarcodeRoute.Container

        assertEquals("00368540100014", containerRoute.scannedCode)
        assertEquals("LOT123", containerRoute.firstBottle.lotNumber)
        assertEquals("12-31-2025", containerRoute.firstBottle.expirationDate)
        assertNull(containerRoute.firstBottle.serialNumber)
        assertEquals(SCAN_IMAGE_PATH, containerRoute.firstBottle.barcodeImagePath)
    }

    @Test
    fun `a plain container barcode gives a count-only first bottle`() {
        val containerRoute = route(PLAIN_UPC, DispenseStage.PRE_NDC) as BarcodeRoute.Container

        assertEquals("00368540100014", containerRoute.scannedCode)
        assertNull(containerRoute.firstBottle.lotNumber)
        assertNull(containerRoute.firstBottle.expirationDate)
        assertNull(containerRoute.firstBottle.serialNumber)
        assertEquals(SCAN_IMAGE_PATH, containerRoute.firstBottle.barcodeImagePath)
    }

    @Test
    fun `a container read with no valid GTIN-14 is ignored`() {
        assertEquals(BarcodeRoute.Ignored, route("AB", DispenseStage.PRE_NDC))
    }

    @Test
    fun `the counting steps read a second bottle`() {
        assertEquals(
            BarcodeRoute.Bottle(GS1_CONTAINER_LABEL, SCAN_IMAGE_PATH),
            route(GS1_CONTAINER_LABEL, DispenseStage.COUNTING, StepState.TARGET_VERIFICATION),
        )
        assertEquals(
            BarcodeRoute.Bottle(GS1_CONTAINER_LABEL, SCAN_IMAGE_PATH),
            route(GS1_CONTAINER_LABEL, DispenseStage.COUNTING, StepState.TARGET_REVERIFICATION),
        )
    }

    @Test
    fun `the vial step reads the vial label until its photo is taken`() {
        assertEquals(
            BarcodeRoute.VialLabel(RAW_RX_LABEL),
            route(RAW_RX_LABEL, DispenseStage.COUNTING, StepState.VIAL, isVialStillShown = false),
        )
        assertEquals(
            BarcodeRoute.Ignored,
            route(RAW_RX_LABEL, DispenseStage.COUNTING, StepState.VIAL, isVialStillShown = true),
        )
    }

    @Test
    fun `the stock bottle counts read no barcode`() {
        assertEquals(BarcodeRoute.Ignored, route(GS1_CONTAINER_LABEL, DispenseStage.COUNTING, StepState.CONTAINER_INITIATE))
        assertEquals(BarcodeRoute.Ignored, route(GS1_CONTAINER_LABEL, DispenseStage.COUNTING, StepState.CONTAINER_PENDING))
    }

    // ─────────────────────────── readsBarcodes ───────────────────────────

    @Test
    fun `every stage before counting reads barcodes`() {
        listOf(DispenseStage.QUEUE, DispenseStage.PRE_RX, DispenseStage.PRE_NDC).forEach { stage ->
            assertTrue(routing.readsBarcodes(stage, StepState.CONTAINER_INITIATE, isVialStillShown = true))
        }
    }

    @Test
    fun `counting reads barcodes on the counts and on the vial before its photo`() {
        assertTrue(routing.readsBarcodes(DispenseStage.COUNTING, StepState.TARGET_VERIFICATION, isVialStillShown = false))
        assertTrue(routing.readsBarcodes(DispenseStage.COUNTING, StepState.TARGET_REVERIFICATION, isVialStillShown = false))
        assertTrue(routing.readsBarcodes(DispenseStage.COUNTING, StepState.VIAL, isVialStillShown = false))
        assertFalse(routing.readsBarcodes(DispenseStage.COUNTING, StepState.VIAL, isVialStillShown = true))
        assertFalse(routing.readsBarcodes(DispenseStage.COUNTING, StepState.CONTAINER_INITIATE, isVialStillShown = false))
        assertFalse(routing.readsBarcodes(DispenseStage.COUNTING, StepState.CONTAINER_PENDING, isVialStillShown = false))
    }

    @Test
    fun `canada routes an 8-digit DIN on the container step as scanned`() {
        val canadaRouting = DispenseBarcodeRouting(ScanDecoder(BarcodeDecoder()), Region.CA)

        val containerRoute = canadaRouting.route("00013285", SCAN_IMAGE_PATH, DispenseStage.PRE_NDC, StepState.TARGET_VERIFICATION, false)
            as BarcodeRoute.Container

        assertEquals("00013285", containerRoute.scannedCode)
        assertNull(containerRoute.firstBottle.lotNumber)
    }

    @Test
    fun `US ignores a 7-digit code on the container step`() {
        assertEquals(BarcodeRoute.Ignored, route("1234567", DispenseStage.PRE_NDC))
    }
}
