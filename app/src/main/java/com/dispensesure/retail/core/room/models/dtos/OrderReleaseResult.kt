package com.dispensesure.retail.core.room.models.dtos

import com.dispensesure.retail.core.room.models.enums.CountStatus

/** What an ORC|RL release changed, for ORDER_RELEASED: the status it released from and the rows updated. */
data class OrderReleaseResult(
    val oldStatus: CountStatus?,
    val rowsChanged: Int
)
