package com.rite.pillcounting.core.room.models.dtos

import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType

/** Row for (status, countType) aggregate. */
data class StatusTypeCount(
    val status: CountStatus,
    val isDispense: Boolean,
    val cnt: Int
)