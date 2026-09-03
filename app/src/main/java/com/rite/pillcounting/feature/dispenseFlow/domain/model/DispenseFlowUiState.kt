package com.rite.pillcounting.feature.dispenseFlow.domain.model

import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.QueueItem

enum class DispenseStage { QUEUE, PRE_RX, PRE_NDC, COUNTING }

/** Rx label values needed to create a standalone dispense txn once the user taps Proceed. */
data class StandaloneRxDraft(
    val drugId: Long?,
    val rxNo: String,
    val refillNo: String?,
    val bucket: String?,
    val targetCount: Int,
)

data class DispenseFlowUiState(
    val stage: DispenseStage = DispenseStage.PRE_RX,
    // True once a resume/HL7 entry has finished resolving its real start stage
    // (COUNTING / PRE_NDC / fallback). Until then the screen shows a loading gate
    // instead of the default PRE_RX ("Scan Rx Label") UI, so that stage doesn't
    // flash before the async init jumps to the resumed step. Irrelevant for plain
    // PRE_RX entries, which never wait on it.
    val initResolved: Boolean = false,
    val queueItems: List<QueueItem.Dispense> = emptyList(),
    val selectedQueueFilter: KpiFilter? = null,
    val scanType: String = CountType.FIXED.toString(),

    val drugName: String = "",
    val drugImage: String = "",
    val ndc: String = "",
    val rxNo: String? = null,
    val refillNo: String? = null,
    val qty: String? = null,
    val selectedBucketId: String = "",

    // GS1 lot/exp/serial decoded off the NDC container scan, staged here until the
    // scan resolves (auto-confirm or after the NDC/equivalence sheet), then written
    // once as the txn's first bottle entry in [advanceToCountingStage].
    val pendingFirstBottle: BottleInfo? = null,

    val ndcScannedValue: String = "",
    val ndcDrugName: String = "",
    val ndcPackageQty: Int? = null,
    val ndcDrugType: String? = null,
    // Strength + dosage form from the NDC drug API (active_ingredients[0].strength
    // and dosage_form[0]). Surfaced on the NDC verification sheet.
    val ndcStrength: String? = null,
    val ndcDosageForm: String? = null,

    val showRxDetails: Boolean = false,
    // Set when a scanned RX resolves to an existing PARTIAL transaction and the
    // RX verification sheet is shown. Holds the stage to advance to when the user
    // taps Proceed (PRE_NDC if the NDC isn't verified yet, else COUNTING). The txn
    // already exists, so Proceed resumes it rather than creating a new one.
    val pendingRxResumeStage: DispenseStage? = null,
    // Rx label staged while the verification sheet is up. Nothing is written to
    // pill_count_txn until the user taps Proceed, so Cancel / back leaves no row.
    val pendingStandaloneRx: StandaloneRxDraft? = null,
    val showNdcDetails: Boolean = false,
    val showInvalidScanDialog: Boolean = false,
    val showNdcNotFoundDialog: Boolean = false,
    val showNdcEquivalenceDialog: Boolean = false,

    // Transient toast signals — bumped to trigger a one-shot toast in the UI.
    val scanNdcToastTick: Int = 0,
    val ndcMismatchToastTick: Int = 0,
    val txnNotFoundToastTick: Int = 0,
    // Fired when a standalone Rx label's NDC matches no drug. No sheet is shown.
    val rxDrugNotFoundToastTick: Int = 0,
    // Fired when a scanned NDC is rejected by the batch PMS allowlist.
    val ndcNotAllowedToastTick: Int = 0,
    val ndcNotAllowedValue: String = "",
    // Fired during the VIAL step when a scanned vial barcode's RX does not match
    // the active transaction's RX number.
    val vialRxMismatchToastTick: Int = 0,
    // Fired when a scanned RX label's most recent transaction is already COMPLETED /
    // FORCE_COMPLETED — blocks re-dispensing the same fill instead of creating a duplicate.
    val rxAlreadyCompletedToastTick: Int = 0,

    val isLoading: Boolean = false,
    val error: String? = null,

    // HL7/PMS-driven entry: when true the user landed here from a PMS
    // notification, the txn already exists, and the RX scan stage is skipped.
    val isFromHl7: Boolean = false,
    // Expected NDC from HL7 (= the drug PMS told us to dispense). Used to
    // detect substitute / mismatch when the user scans the container.
    val hl7ExpectedNdc: String? = null,
    val isSubstituteConfirmed: Boolean = false,

    val txnId: Long = 0L,

    // Stock-count (normalized) session: set when a REGULAR loose-count line is created so
    // the shared PillScanningViewModel can count into the BottleInfo line. Stock counts do
    // not use pill_count_txn, so [txnId] stays 0 for them.
    val stockTxnId: Long = 0L,
    val stockBottleId: Long = 0L,
    val stockDrugId: Long = 0L,

    val isHazardous: Boolean = false,

    // Stock-count only: shown when the user scans an RX label instead of an NDC container.
    val showRxScannedInStockCountDialog: Boolean = false,

    // RX duplicate check: shown when a scanned RX already has a PARTIAL txn in the DB.
    val showContinueRxDialog: Boolean = false,
    // RX on-hold check: shown when the found txn is in ON_HOLD status.
    val showOnHoldDialog: Boolean = false,

    // Stock-count batch association. 0L means no batch (dispense flow or stock count
    // started without a batch context).
    val batchId: Long = 0L,

    // NDC allowlist for batch Scan-Pills from a PMS batch. Non-empty only when the
    // batch was PMS-sourced (or the user had a specific active NDC on the card).
    // onNdcBarcodeRead rejects any scanned NDC that isn't in this set.
    // Empty = no restriction (manually-started batch or plain dispense flow).
    val allowedNdcs: Set<String> = emptySet(),

    // One-shot signal: set true when a transaction completes and the queue is empty.
    val navigateToDashboard: Boolean = false,
)
