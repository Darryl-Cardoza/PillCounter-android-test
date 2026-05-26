package com.rite.pillcounting.feature.dispenseFlow.domain.data

import android.graphics.Bitmap
import com.rite.pillcounting.core.models.StepState

sealed interface PillScanningEvent {
    data class AddTransactionDetailClicked(val filteredCount: Int, val stepType: StepState) : PillScanningEvent
    data class TransactionDetailDeleted(val txnDetailId: Long) : PillScanningEvent
    data class AllTransactionDetailsDeleted(val stepType: StepState) : PillScanningEvent
    data object RescanClicked : PillScanningEvent
    data object PauseClicked : PillScanningEvent
    data object DoneClicked : PillScanningEvent
    data class FinalDone(val stepType: StepState, val totalCount: Int) : PillScanningEvent
    data object ConfirmDone : PillScanningEvent
    data object CancelDone : PillScanningEvent
    data object NoteSkip : PillScanningEvent
    data class NoteSaved(val note: String) : PillScanningEvent
    data class AddVialPhotoInTxn(val filteredCount: Int, val bitmap: Bitmap) : PillScanningEvent
}