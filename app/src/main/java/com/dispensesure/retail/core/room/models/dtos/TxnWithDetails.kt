package com.dispensesure.retail.core.room.models.dtos

import androidx.room.Relation
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.models.PillCountTxnDetailsEntity

data class TxnWithDetails(
    val txnId: Long,
    val drugName: String?,
    val drugId: Long,
    val ndc: String?,
    val targetCount: Int?,
    val note: String?,
    val createdAt: Long,
    val bottleInfoListJson: String?,
    val totalPillCount: Int,
    val isDispense: Boolean,
    val drugType: String?,
    val strength: String? = null,
    val dosageForm: String? = null,
    val bucketId: String? = null,
    @Relation(
        parentColumn = "txnId",
        entityColumn = "txnId",
        entity = PillCountTxnDetailsEntity::class,
        projection = ["txnDetailsId", "txnId", "pillCount", "imagePath", "type"]
    )
    val txnDetails: List<TxnDetailInfo>,
    val isComingFromHL7 : Boolean,
    val isSubstitute: Boolean = false,
    val requestedDrugName: String? = null,
    val requestedNdc: String? = null,
    val workflowStep: String? = null,
    val drugImage: String? = "",
)

data class TxnDetailInfo(
    val txnDetailsId: Long = 0L,
    val txnId: Long?,
    val pillCount: Int?,
    val imagePath: String?,
    val type: StepState?,
)
