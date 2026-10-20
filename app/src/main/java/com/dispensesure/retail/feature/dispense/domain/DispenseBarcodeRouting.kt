package com.dispensesure.retail.feature.dispense.domain

import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage

/** Where a barcode read on the dispense screen goes. */
sealed interface BarcodeRoute {
    data class RxLabel(val rawValue: String) : BarcodeRoute

    // firstBottle holds the GS1 lot / expiry / serial, saved as the txn's first bottle.
    // scannedCode is a GTIN-14, or for Canada an 8-digit DIN as scanned.
    data class Container(val scannedCode: String, val firstBottle: DispenseBottle) : BarcodeRoute

    data object RxLabelOnContainerStep : BarcodeRoute
    data class Bottle(val rawValue: String, val imagePath: String?) : BarcodeRoute
    data class VialLabel(val rawValue: String) : BarcodeRoute

    // False positive, wrong format, or a step that takes no barcode.
    data object Ignored : BarcodeRoute
}

/** Which handler gets a scanned value, per stage and counting step. */
class DispenseBarcodeRouting(private val scanDecoder: ScanDecoder, private val region: Region) {

    /** True when the stage or step reads barcodes at all; the screen runs the analyzer only then. */
    fun readsBarcodes(stage: DispenseStage, currentStep: StepState, isVialStillShown: Boolean): Boolean =
        when (stage) {
            DispenseStage.COUNTING ->
                isBottleRescanStep(currentStep) || isVialCaptureStep(currentStep, isVialStillShown)
            else -> true
        }

    /**
     * Decode a raw scanned barcode and pick the stage handler for it.
     * [BarcodeRoute.Ignored] when it was dropped (false positive, wrong format,
     * or the step doesn't accept barcodes). The caller resumes the analyzer
     * on a dropped read — without that, a single dropped read leaves the
     * analyzer self-paused forever.
     */
    fun route(
        rawValue: String,
        imagePath: String?,
        stage: DispenseStage,
        currentStep: StepState,
        isVialStillShown: Boolean,
    ): BarcodeRoute {
        if (rawValue.isBlank()) return BarcodeRoute.Ignored
        return when (stage) {
            DispenseStage.QUEUE,
            DispenseStage.PRE_RX -> BarcodeRoute.RxLabel(rawValue)
            DispenseStage.PRE_NDC -> containerRoute(rawValue, imagePath)
            DispenseStage.COUNTING -> when {
                isBottleRescanStep(currentStep) -> BarcodeRoute.Bottle(rawValue, imagePath)
                isVialCaptureStep(currentStep, isVialStillShown) -> BarcodeRoute.VialLabel(rawValue)
                else -> BarcodeRoute.Ignored
            }
        }
    }

    private fun containerRoute(rawValue: String, imagePath: String?): BarcodeRoute {
        // Pipe-delimited payloads are the RX-label template
        // ({RXNO}|{NDCNO}|{QTY}|{BUCKET}) — never a valid GTIN / GS1.
        // Nudge with a toast and resume scanning.
        if (rawValue.contains('|')) return BarcodeRoute.RxLabelOnContainerStep
        val scan = scanDecoder.decode(rawValue, region)
        val scannedCode = scan.lookupCode?.takeIf { it.isNotBlank() } ?: return BarcodeRoute.Ignored
        // First-bottle info for this txn: a plain (non-GS1) barcode carries no
        // lot/exp/serial, so the bottle entry is count-only in that case.
        val firstBottle = DispenseBottle(
            lotNumber = scan.lot,
            expirationDate = scan.expiry,
            serialNumber = scan.serial,
            barcodeImagePath = imagePath,
        )
        return BarcodeRoute.Container(scannedCode, firstBottle)
    }

    // Actively counting pills: keep the barcode scanner live so a rescan of the same NDC
    // (a second bottle) can be detected mid-count.
    private fun isBottleRescanStep(step: StepState): Boolean =
        step == StepState.TARGET_VERIFICATION || step == StepState.TARGET_REVERIFICATION

    // VIAL step with no photo taken yet: run the barcode analyzer over the live
    // frames so a vial whose RX matches the active transaction can be captured
    // automatically. Once a photo exists (auto or manual) we stop scanning.
    private fun isVialCaptureStep(step: StepState, isVialStillShown: Boolean): Boolean =
        step == StepState.VIAL && !isVialStillShown
}
