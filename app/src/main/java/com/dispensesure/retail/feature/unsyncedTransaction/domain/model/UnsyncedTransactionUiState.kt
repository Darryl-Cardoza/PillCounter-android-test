package com.dispensesure.retail.feature.unsyncedTransaction.domain.model

import com.dispensesure.retail.feature.history.domain.model.BatchSummary

data class UnsyncedTransactionUiState(
    val dispenseList: List<CountItem> = emptyList(),
    val batchList: List<BatchSummary> = emptyList()
)
