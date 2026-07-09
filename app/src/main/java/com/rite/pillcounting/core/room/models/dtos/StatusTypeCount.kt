package com.rite.pillcounting.core.room.models.dtos

import com.rite.pillcounting.core.room.models.enums.CountStatus

/** Row for (status, isDispense) aggregate. */
data class StatusTypeCount(
    val status: CountStatus,
    val isDispense: Boolean,
    val cnt: Int
)