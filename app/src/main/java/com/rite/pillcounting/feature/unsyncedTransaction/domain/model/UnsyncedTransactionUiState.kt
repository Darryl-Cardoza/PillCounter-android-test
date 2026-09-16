package com.rite.pillcounting.feature.unsyncedTransaction.domain.model

import com.rite.pillcounting.feature.countResume.domain.model.CountItem
import com.rite.pillcounting.feature.history.domain.model.BatchSummary

data class UnsyncedTransactionUiState(
    val dispenseList: List<CountItem> = emptyList(),
    val batchList: List<BatchSummary> = emptyList()
)
