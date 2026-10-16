package com.dispensesure.retail.feature.dispense.domain.model

import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.feature.dashboard.domain.model.KpiFilter
import com.dispensesure.retail.feature.dashboard.domain.model.QueueItem

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
    // True while a resume/HL7 entry is still resolving its real start stage
    // (COUNTING / PRE_NDC / fallback). Until then the screen shows a loading gate
    // instead of the default PRE_RX ("Scan Rx Label") UI, so that stage doesn't
    // flash before the async init jumps to the resumed step. Irrelevant for plain
    // PRE_RX entries, which never wait on it.
    val isResolvingEntry: Boolean = false,
    // Opened from the queue: backing out of an Rx scan returns to the queue.
    val isFromQueue: Boolean = false,
    val queueItems: List<QueueItem.Dispense> = emptyList(),
    val selectedQueueFilter: KpiFilter? = null,

    val drugName: String = "",
    val drugImage: String = "",
    val ndc: String = "",
    val rxNo: String? = null,
    val refillNo: String? = null,
    val qty: String? = null,
    val selectedBucketId: String = "",

    // GS1 lot/exp/serial decoded off the NDC container scan, staged here until the
    // scan resolves (auto-confirm or after the equivalence dialog), then written
    // once as the txn's first bottle entry by DispenseFlowRepository.markContainerVerified.
    val pendingFirstBottle: DispenseBottle? = null,

    val ndcScannedValue: String = "",
    val ndcDrugName: String = "",
    val ndcPackageQty: Int? = null,
    val ndcDrugType: String? = null,
    // Strength + dosage form from the NDC drug API (active_ingredients[0].strength
    // and dosage_form[0]). Surfaced on the Rx sheet and the container step's details bar.
    val ndcStrength: String? = null,
    val ndcDosageForm: String? = null,

    val showRxDetails: Boolean = false,
    // Set when a scanned RX resolves to an existing PARTIAL transaction and the
    // RX verification sheet is shown. Holds the stage to advance to when the user
    // taps Proceed (PRE_NDC if the NDC isn't verified yet, else COUNTING). The txn
    // already exists, so Proceed resumes it rather than creating a new one.
    val pendingRxResumeStage: DispenseStage? = null,
    // Rx label staged while the verification sheet is up. Nothing is written to
    // dispense_txn until the user taps Proceed, so Cancel / back leaves no row.
    val pendingStandaloneRx: StandaloneRxDraft? = null,
    val showNdcEquivalenceDialog: Boolean = false,

    val isLoading: Boolean = false,

    // Expected NDC from HL7 (= the drug PMS told us to dispense). Used to
    // detect substitute / mismatch when the user scans the container.
    val hl7ExpectedNdc: String? = null,
    val isSubstituteConfirmed: Boolean = false,

    val txnId: Long = 0L,

    val isHazardous: Boolean = false,

    // RX on-hold check: shown when the found txn is in ON_HOLD status.
    val showOnHoldDialog: Boolean = false,
)
