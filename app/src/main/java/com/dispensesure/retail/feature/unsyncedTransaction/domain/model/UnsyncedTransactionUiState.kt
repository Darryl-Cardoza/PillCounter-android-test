package com.dispensesure.retail.feature.unsyncedTransaction.domain.model

import com.dispensesure.retail.feature.countResume.domain.model.CountItem
import com.dispensesure.retail.feature.history.domain.model.BatchSummary

data class UnsyncedTransactionUiState(
    val dispenseList: List<CountItem> = emptyList(),
    val batchList: List<BatchSummary> = emptyList()
)
