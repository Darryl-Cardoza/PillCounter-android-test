package com.rite.pillcounting.feature.unsyncedTransaction.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.utils.common.DateFormats
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.utils.common.formatDateToUSFormat
import com.rite.pillcounting.core.utils.compose.DrugCountRow
import com.rite.pillcounting.core.utils.compose.DrugCountRowData
import com.rite.pillcounting.feature.countResume.domain.model.CountItem
import com.rite.pillcounting.feature.history.domain.model.BatchSummary
import com.rite.pillcounting.feature.history.presentation.compose.BatchHistoryRow
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun UnsyncedTransactionList(
    dispenseList: List<CountItem>,
    batchList: List<BatchSummary>,
    onButtonClick: () -> Unit = {}
) {
    val dimens = AppTheme.dimens
    val isEmpty = dispenseList.isEmpty() && batchList.isEmpty()

    if (isEmpty) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.no_unsynced_transactions),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.extendedColors.textColor
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = dimens.small, end = dimens.small, bottom = dimens.extraSmall)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (dispenseList.isNotEmpty()) {
                    item {
                        SectionHeader(
                            text = stringResource(R.string.dispense),
                            count = dispenseList.size
                        )
                    }
                    items(dispenseList, key = { "dispense_${it.id}" }) { item ->
                        DrugCountRow(
                            data = DrugCountRowData(
                                barcodeImage = BottleInfoJson.decode(item.bottleInfoListJson).firstOrNull()?.barcodeImagePath,
                                ndc = item.ndc,
                                drugType = item.drugType,
                                drugName = item.name,
                                date = item.date,
                                bucketId = item.bucketId,
                                pillCount = item.pillCount,
                                targetCount = item.target,
                                isDispense = item.isDispense,
                                isComingFromHL7 = item.isComingFromHL7
                            ),
                            onClick = {}
                        )
                    }
                }

                if (batchList.isNotEmpty()) {
                    item {
                        SectionHeader(
                            text = stringResource(R.string.stock),
                            count = batchList.size,
                            topPadding = if (dispenseList.isNotEmpty()) 8.dp else 0.dp
                        )
                    }
                    items(batchList, key = { "batch_${it.batchId}" }) { batch ->
                        BatchHistoryRow(
                            title = batch.batchId.toString(),
                            dateTime = formatDateToUSFormat(
                                batch.createdAt.toFormattedDate(),
                                outputPattern = DateFormats.MM_DD_YYYY_HH_MM_A
                            ),
                            bucketId = batch.bucketId,
                            count = batch.uniqueNdcCount.toString(),
                            isPrescription = batch.requestIdFromPMS != null,
                            onBatchClick = {}
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = onButtonClick,
                    shape = RoundedCornerShape(dimens.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colorScheme.primary,
                        contentColor = AppTheme.extendedColors.textColor
                    ),
                    modifier = Modifier
                        .height(dimens.buttonHeight)
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sync_all),
                        fontSize = 14.sp,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    count: Int,
    topPadding: Dp = 0.dp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = topPadding, bottom = 4.dp, start = 10.dp)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.size(6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(22.dp)
                .background(color = MaterialTheme.colorScheme.secondary, shape = CircleShape)
        ) {
            Text(
                text = count.toString(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                lineHeight = 10.sp
            )
        }
    }
}
