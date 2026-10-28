package com.dispensesure.retail.feature.dispense.domain.model

import android.graphics.Bitmap
import com.dispensesure.retail.core.models.StepState

sealed interface DispenseCountAction {
    data class AddTransactionDetailClicked(val filteredCount: Int, val stepType: StepState) : DispenseCountAction
    data class TransactionDetailDeleted(val txnDetailId: Long) : DispenseCountAction
    data class AllTransactionDetailsDeleted(val stepType: StepState) : DispenseCountAction
    data object RescanClicked : DispenseCountAction
    data object PauseClicked : DispenseCountAction
    data object DoneClicked : DispenseCountAction
    data class FinalDone(val stepType: StepState, val totalCount: Int) : DispenseCountAction
    data object NoteSkip : DispenseCountAction
    data class NoteSaved(val note: String) : DispenseCountAction
    data class AddVialPhotoInTxn(val filteredCount: Int, val bitmap: Bitmap) : DispenseCountAction
    data object ConfirmAddBottle : DispenseCountAction
    data object CancelAddBottle : DispenseCountAction
    data object ConfirmReplaceBottle : DispenseCountAction
    data object CancelReplaceBottle : DispenseCountAction
}
