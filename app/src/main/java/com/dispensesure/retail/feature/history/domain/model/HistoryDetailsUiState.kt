package com.dispensesure.retail.feature.history.domain.model

import com.dispensesure.retail.core.room.models.dtos.DispenseTxnDetail

data class HistoryDetailsUiState(
    val txnInfo: DispenseTxnDetail? = null
)
