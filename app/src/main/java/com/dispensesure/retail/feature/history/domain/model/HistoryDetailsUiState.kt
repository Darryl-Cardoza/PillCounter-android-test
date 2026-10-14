package com.dispensesure.retail.feature.history.domain.model

import com.dispensesure.retail.core.room.models.dtos.TxnWithDetails

data class HistoryDetailsUiState(
    val txnInfo: TxnWithDetails? = null
)
