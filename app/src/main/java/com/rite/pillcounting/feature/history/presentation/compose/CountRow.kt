package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.runtime.Composable
import com.rite.pillcounting.core.utils.common.DateFormats
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.utils.common.formatDateToUSFormat
import com.rite.pillcounting.core.utils.compose.DrugCountRow
import com.rite.pillcounting.core.utils.compose.DrugCountRowData
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto

@Composable
fun CountRow(
    rowData: TxnWithDrugDto,
    onTxnClick: () -> Unit
) {
    DrugCountRow(
        data = DrugCountRowData(
            barcodeImage = rowData.barcodeImage,
            ndc = rowData.ndc,
            drugType = rowData.drugType,
            drugName = rowData.drugName ?: "",
            date = formatDateToUSFormat(
                rowData.createdAt.toFormattedDate(),
                outputPattern = DateFormats.MM_DD_YYYY_HH_MM_A
            ),
            bucketId = rowData.bucketId,
            pillCount = rowData.pillCount ?: 0,
            targetCount = rowData.targetCount ?: 0,
            countType = rowData.countType
        ),
        onClick = onTxnClick
    )
}
