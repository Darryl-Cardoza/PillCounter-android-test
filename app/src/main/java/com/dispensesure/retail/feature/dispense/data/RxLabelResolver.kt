package com.dispensesure.retail.feature.dispense.data

import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import com.dispensesure.retail.core.utils.common.ParsedScanData
import com.dispensesure.retail.core.utils.common.parseScanData
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.dispense.domain.model.StandaloneRxDraft
import javax.inject.Inject

/** What a scanned Rx label leads to. */
sealed interface RxScanOutcome {
    // Not the configured Rx label format, or a field is missing.
    data object Invalid : RxScanOutcome

    // The fill on the label already went out.
    data object AlreadyCompleted : RxScanOutcome

    // No order for the Rx, and the app may not start one itself.
    data object NotFound : RxScanOutcome

    // Standalone mode: the label's NDC matches no drug.
    data object DrugNotFound : RxScanOutcome

    // The label is for a different drug than the order.
    data object NdcMismatch : RxScanOutcome

    data object OnHold : RxScanOutcome

    // A PARTIAL order exists. scannedLabel fills what the order leaves blank.
    data class Resume(
        val txn: DispenseTxnEntity,
        val drug: DrugMasterEntity?,
        val scannedLabel: ParsedScanData,
    ) : RxScanOutcome

    // Standalone mode: the label is staged; nothing is saved until Proceed.
    data class Standalone(val draft: StandaloneRxDraft, val drug: DrugMasterEntity) : RxScanOutcome
}

/** Reads an Rx label and finds its order (or, in standalone mode, its drug). */
class RxLabelResolver @Inject constructor(
    private val preferenceHelper: PreferenceHelper,
    private val dispenseFlowRepository: DispenseFlowRepository,
    private val drugResolver: DrugResolver,
) {

    private val logger = AppLogger("RxLabelResolver")

    /**
     * Parses out NDC / RX / qty using the configured regex, validates, then
     * looks up the order for the RX. Standalone mode looks up the drug locally
     * first and falls back to the server.
     */
    suspend fun resolve(rawValue: String): RxScanOutcome {
        if (rawValue.isBlank()) return RxScanOutcome.Invalid

        val parsed = parseLabel(rawValue) ?: return RxScanOutcome.Invalid
        val parsedNdc = parsed.ndcNo
        val qty = parsed.qty
        val rxNo = parsed.rxNo
        val refillNo = parsed.refillNo
        val bucket = parsed.bucket

        val qtyInt = qty?.toIntOrNull()
        if (parsedNdc.isNullOrBlank() || qty.isNullOrBlank() || rxNo.isNullOrBlank() || qtyInt == null) {
            return RxScanOutcome.Invalid
        }

        // Block re-dispensing a fill that's already gone out. getActiveByRxNo (below)
        // only matches PARTIAL/ON_HOLD, so without this check a COMPLETED Rx would
        // silently fall through to "no active txn" and spawn a brand new PARTIAL txn
        // on every rescan. Keyed by (rxNo, refillNo) when the label carries a fill
        // number, so a genuinely new refill isn't blocked by the prior fill's
        // COMPLETED status — only falls back to the bare rxNo lookup when the label
        // doesn't encode a refill number at all.
        val mostRecentTxn = if (!refillNo.isNullOrBlank()) {
            dispenseFlowRepository.loadTxnByRxNoAndFill(rxNo, refillNo)
        } else {
            dispenseFlowRepository.loadMostRecentTxnByRxNo(rxNo)
        }
        if (mostRecentTxn?.status == CountStatus.COMPLETED || mostRecentTxn?.status == CountStatus.FORCE_COMPLETED) {
            return RxScanOutcome.AlreadyCompleted
        }

        // Check if this RX has an active local transaction (PARTIAL or ON_HOLD).
        val existingTxn = dispenseFlowRepository.loadActiveTxnByRxNo(rxNo)
        if (existingTxn == null) {
            // No PMS-created transaction found. In standalone mode, no PMS will
            // ever send this txn, so the app is expected to originate the
            // dispense itself off the scanned Rx label, rather than wait on an
            // HL7 order that will never arrive.
            if (!preferenceHelper.isStandaloneMode()) return RxScanOutcome.NotFound
            // Only stage the label here. The txn is created on Proceed, so
            // cancelling or backing out leaves nothing in the DB.
            return standaloneOutcome(
                parsedNdc = parsedNdc,
                rxNo = rxNo,
                refillNo = refillNo,
                bucket = bucket,
                targetCount = qtyInt,
            )
        }
        val drug = dispenseFlowRepository.loadDrug(existingTxn.drugId)
        val gatedStatus = existingTxn.status == CountStatus.PARTIAL ||
                existingTxn.status == CountStatus.ON_HOLD
        // The label's NDC has to match the drug PMS ordered. No drug on
        // the txn means nothing to compare, so the check is skipped.
        if (gatedStatus && drug != null && !ndcDigitsMatch(parsedNdc, drug.ndc)) {
            logger.w("Rx label NDC does not match the txn drug: label=$parsedNdc txnNdc=${drug.ndc} rxNo=$rxNo txn=${existingTxn.txnId}")
            return RxScanOutcome.NdcMismatch
        }
        return when (existingTxn.status) {
            CountStatus.ON_HOLD -> RxScanOutcome.OnHold
            CountStatus.PARTIAL -> RxScanOutcome.Resume(existingTxn, drug, parsed)
            else -> RxScanOutcome.NotFound
        }
    }

    /** The label's Rx number, trimmed; null with no label format set or no Rx on the label. */
    fun rxNoOf(rawValue: String): String? = parseLabel(rawValue)?.rxNo?.trim()?.takeIf { it.isNotEmpty() }

    // Null when no label format is configured.
    private fun parseLabel(rawValue: String): ParsedScanData? {
        val barcodeRegex = preferenceHelper.getBarcodeRegex().orEmpty()
        if (barcodeRegex.isBlank()) return null
        return parseScanData(barcodeRegex, rawValue)
    }

    /** Resolves the drug for a standalone Rx label. Writes nothing to dispense_txn. */
    private suspend fun standaloneOutcome(
        parsedNdc: String,
        rxNo: String,
        refillNo: String?,
        bucket: String?,
        targetCount: Int,
    ): RxScanOutcome {
        val drug = (drugResolver.resolve(
            parsedNdc,
            allowNoName = true,
        ) as? DrugLookup.Found)?.drug
        // No drug to verify the label against, so reject the scan instead of
        // staging a blank Rx.
        if (drug == null) {
            logger.w("Standalone dispense: drug could not be resolved for ndc=$parsedNdc rxNo=$rxNo — rejecting the scan")
            return RxScanOutcome.DrugNotFound
        }
        logger.i("[HAZARDOUS] Standalone RX staged: ndc=$parsedNdc rx=$rxNo drug=${drug.drugName} isHazardous=${drug.isHazardous} — awaiting Proceed")
        return RxScanOutcome.Standalone(
            draft = StandaloneRxDraft(
                drugId = drug.drugId,
                rxNo = rxNo,
                refillNo = refillNo,
                bucket = bucket,
                targetCount = targetCount,
            ),
            drug = drug,
        )
    }

    /**
     * Compares two NDCs on digits only, because labels print them dashed and the
     * server does not. Null or digit-free input never matches.
     */
    private fun ndcDigitsMatch(labelNdc: String, txnNdc: String?): Boolean {
        val label = labelNdc.filter { it.isDigit() }
        val txn = txnNdc?.filter { it.isDigit() }
        return label.isNotEmpty() && label == txn
    }
}
