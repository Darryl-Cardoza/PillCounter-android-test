package com.rite.pillcounting.feature.dispenseFlow.domain.model

import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.feature.dispenseFlow.presentation.logic.GloveDetection
import com.rite.pillcounting.feature.dispenseFlow.presentation.logic.TrayColor

/**
 * Represents the UI state for the pill counting process.
 *
 * Tracks progress of scanning and counting pills, including batch information,
 * detected pills, and system flags such as pause or loading status.
 *
 * @param scanType Whether this is a FIXED or REGULAR scan ([CountType]).
 * @param drugName Name of the drug being scanned (e.g., "Crocin 50mg").
 * @param totalCount Total number of pills counted across all batches so far.
 * @param targetCount Expected total number of pills to be scanned (ignored if FIXED).
 * @param currentScanCount Number of pills detected in the current scan session.
 * @param txnDetailHistory List of completed batches with their counts and thumbnails.
 * @param detectedPills List of pills detected in the current camera frame.
 * @param isPaused Whether the scanning process is currently paused.
 * @param isLoading Whether a loading state is active (e.g., processing results).
 * @param error Error message if an error occurred during scanning or counting.
 */
data class PillScanningUiState(
    val scanType: String = "REGULAR",
    val drugName: String = "",
    val totalCount: Int = 0,
    val targetCount: Int = 0,
    val currentScanCount: Int = 0,
    val txnDetailHistory: List<TxnDetail> = emptyList(),
    val detectedPills: List<DetectedPill> = emptyList(),
    val filteredPills: List<DetectedPill> = emptyList(),
    val gloveDetections: List<GloveDetection> = emptyList(),
    val isPaused: Boolean = false,
    val isLoading: Boolean = false,
    val restrictAdd: Boolean = false,
    val showConfirmDialog: Boolean = false,
    val showNoTransaction: Boolean = false,
    val showTargetCountDialog: Boolean = false,
    val showIdleOverlay: Boolean = false,
    val showNotesDialog: Boolean = false,
    val imageFrameWidth: Int = 0,
    val imageFrameHeight: Int = 0,
    val isAddCooldown: Boolean = false,
    val addCount: Int = 0,
    val showDialogForControl: Boolean = false,
    val showErrorMessage: String? = null,
    val isHl7Txn: Boolean = false,
    val showCountMismatchDialog: Boolean = false,
    val showEndStockCountDialog: Boolean = false,
    val stockCountSessionTotal: Int = 0,
    val pendingTrayColorForClassification: TrayColor? = null,
)