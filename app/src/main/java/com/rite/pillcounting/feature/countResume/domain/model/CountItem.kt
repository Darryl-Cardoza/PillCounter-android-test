package com.rite.pillcounting.feature.countResume.domain.model

import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority

/**
 * Represents a single item in a partial or fixed count list.
 *
 * This class encapsulates the essential information about the item,
 * including its unique identifier, name, quantity, date of counting,
 * and an optional image resource.
 *
 * @property id Unique identifier for the item. Defaults to a randomly generated UUID.
 * @property name Name of the medicine or item.
 * @property pillCount The quantity counted for this item.
 * @property date The timestamp when this count was recorded.
 * @property image Drawable resource ID for the item's icon. Defaults to [R.drawable.logo].
 */
data class CountItem(
    val id: Long,
    val name: String,
    val ndc: String?,
    val drugType: String?,
    val bucketId: String?,
    val pillCount: Int,
    val target: Int,
    val bottleInfoListJson: String?,
    val date: String,
    val image: Int = R.drawable.logo,
    val isComingFromHL7: Boolean,
    val isNdcVerified: Boolean,
    val isDispense: Boolean = false,
    val priority: TxnPriority? = null,
    val strength: String? = null,
    val dosageForm: String? = null,
    val drugImagePath: String? = null
)
