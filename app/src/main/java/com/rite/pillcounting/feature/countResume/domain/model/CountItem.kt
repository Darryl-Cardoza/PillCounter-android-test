package com.rite.pillcounting.feature.countResume.domain.model

import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority

/**
 * Represents a single item in a partial or fixed count list.
 *
 * This class encapsulates the essential information about the item,
 * including its identity, the drug it counted, the quantity, and the
 * drug details the list rows render.
 *
 * @property id Transaction ID of the count.
 * @property name Name of the medicine or item.
 * @property ndc NDC of the counted drug.
 * @property drugType Drug type reported for the NDC.
 * @property bucketId Bucket the count belongs to.
 * @property pillCount The quantity counted for this item.
 * @property target The expected count for this transaction.
 * @property bottleInfoListJson Serialized list of the bottles used in this count.
 * @property date The timestamp when this count was recorded, already formatted.
 * @property image Drawable resource ID for the item's icon. Defaults to [R.drawable.logo].
 * @property isComingFromHL7 True when the transaction originated from an HL7 order.
 * @property isNdcVerified True once the scanned NDC has been verified.
 * @property isDispense True when this is a dispense rather than a plain count.
 * @property priority Priority of the originating transaction, if any.
 * @property strength Strength of the drug, shown in the row.
 * @property dosageForm Dosage form of the drug, shown in the row.
 * @property drugImagePath Local file path of the downloaded drug image, shown in the row.
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
