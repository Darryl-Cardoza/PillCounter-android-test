package com.dispensesure.retail.core.room.models.dtos

import com.dispensesure.retail.core.room.models.enums.CountStatus

/** Row for the per-status dispense count aggregate. */
data class DispenseStatusCount(
    val status: CountStatus,
    val cnt: Int
)
