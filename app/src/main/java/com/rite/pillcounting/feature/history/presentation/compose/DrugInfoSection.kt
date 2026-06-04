package com.rite.pillcounting.feature.history.presentation.compose

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.dtos.TxnDetailInfo
import com.rite.pillcounting.core.utils.common.DateFormats
import com.rite.pillcounting.core.utils.common.formatDateToUSFormat
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.ui.theme.AppTheme
import java.io.File

private fun List<TxnDetailInfo>.forStep(step: StepState): List<TxnDetailInfo> =
    filter { it.type == step }

private fun List<TxnDetailInfo>.sumForStep(step: StepState): Int =
    forStep(step).sumOf { it.pillCount ?: 0 }

private fun List<TxnDetailInfo>.hasStep(step: StepState): Boolean =
    any { it.type == step }

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun DrugInfoSection(
    ndc: String,
    drugName: String,
    expiry: String,
    lotNo: String,
    date: String,
    time: String,
    note: String,
    barcodeImage: String?,
    targetCount: Int?,
    transactionDetails: List<TxnDetailInfo>,
    isFromHl7: Boolean,
    drugType: String?,
    isSubstituted: Boolean = false,
    requestedDrugName: String = "",
    requestedNdc: String = "",
    onImagePreview: (String) -> Unit = {},
) {
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.extendedColors.secondaryBackground)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(responsiveDp(10.dp))
                ) {

                    if (drugType.toString().equals("null")) {
                        // drugType null layout: Pill Count → Drug Details → Vial → Notes

                        if (transactionDetails.hasStep(StepState.TARGET_VERIFICATION)) {
                            SectionBox(
                                title = stringResource(R.string.pill_count),
                                allowCollapse = false,
                                defaultExpanded = true
                            ) {
                                CountSectionContent(
                                    barcodeImage = barcodeImage,
                                    count = transactionDetails.sumForStep(StepState.TARGET_VERIFICATION),
                                    showFraction = targetCount != null,
                                    targetCount = targetCount,
                                    batches = transactionDetails.forStep(StepState.TARGET_VERIFICATION),
                                    isVial = false,
                                    onBatchImageClick = { imagePath -> onImagePreview(imagePath) },
                                    stringResource(R.string.total_count).uppercase()
                                )
                            }
                        }

                        if (isSubstituted) {
                            SectionBox(title = stringResource(R.string.requested_drug_details)) {
                                KeyValueList(
                                    rows = listOf(
                                        stringResource(R.string.drug_name) to requestedDrugName,
                                        stringResource(R.string.ndc).uppercase() to requestedNdc,
                                    )
                                )
                            }
                        }

                        val title = if (isSubstituted) stringResource(R.string.substitute_drug_details) else stringResource(R.string.dispense_drug_details)
                        val drugNameTitle = stringResource(R.string.drug_name)
                        SectionBox(title = title) {
                            KeyValueList(
                                rows = listOf(
                                    drugNameTitle to drugName,
                                    stringResource(R.string.ndc).uppercase() to ndc,
                                    stringResource(R.string.expiry) to expiry,
                                    stringResource(R.string.lotNo) to lotNo,
                                    stringResource(R.string.date) to formatDateToUSFormat(
                                        date,
                                        DateFormats.MM_DD_YYYY
                                    ),
                                    stringResource(R.string.time) to time,
                                )
                            )
                        }

                        if (transactionDetails.hasStep(StepState.VIAL)) {
                            SectionBox(title = stringResource(R.string.vial_capture)) {
                                CountSectionContent(
                                    barcodeImage = null,
                                    count = 0,
                                    showFraction = false,
                                    targetCount = null,
                                    batches = transactionDetails.forStep(StepState.VIAL),
                                    isVial = true,
                                    onBatchImageClick = { imagePath -> onImagePreview(imagePath) },
                                    stringResource(R.string.total_re_count).uppercase()
                                )
                            }
                        }

                        SectionBox(title = stringResource(R.string.notes)) {
                            Text(
                                text = note.ifBlank { "—" },
                                color = AppTheme.extendedColors.textColor,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    } else {

                        if (transactionDetails.hasStep(StepState.CONTAINER_INITIATE)) {
                            SectionBox(
                                title = stringResource(R.string.initial_stock_bottle_count),
                                allowCollapse = false,
                                defaultExpanded = true
                            ) {
                                CountSectionContent(
                                    barcodeImage = barcodeImage,
                                    count = transactionDetails.sumForStep(StepState.CONTAINER_INITIATE),
                                    showFraction = false,
                                    targetCount = null,
                                    batches = transactionDetails.forStep(StepState.CONTAINER_INITIATE),
                                    isVial = false,
                                    onBatchImageClick = { imagePath -> onImagePreview(imagePath) },
                                    stringResource(R.string.total_count).uppercase()
                                )
                            }
                        }

                        if (isSubstituted) {
                            SectionBox(title = stringResource(R.string.requested_drug_details)) {
                                KeyValueList(
                                    rows = listOf(
                                        stringResource(R.string.drug_name) to requestedDrugName,
                                        stringResource(R.string.ndc).uppercase() to requestedNdc,
                                    )
                                )
                            }
                        }

                        val title = if (isSubstituted) stringResource(R.string.substitute_drug_details) else stringResource(R.string.dispense_drug_details)
                        val drugNameTitle = stringResource(R.string.drug_name)
                        SectionBox(title = title) {
                            KeyValueList(
                                rows = listOf(
                                    drugNameTitle to drugName,
                                    stringResource(R.string.ndc).uppercase() to ndc,
                                    stringResource(R.string.expiry) to expiry,
                                    stringResource(R.string.lotNo) to lotNo,
                                    stringResource(R.string.date) to formatDateToUSFormat(
                                        date,
                                        DateFormats.MM_DD_YYYY
                                    ),
                                    stringResource(R.string.time) to time,
                                )
                            )
                        }

                        if (transactionDetails.hasStep(StepState.TARGET_VERIFICATION)) {
                            SectionBox(title = stringResource(R.string.pill_count)) {
                                CountSectionContent(
                                    barcodeImage = barcodeImage,
                                    count = transactionDetails.sumForStep(StepState.TARGET_VERIFICATION),
                                    showFraction = targetCount != null,
                                    targetCount = targetCount,
                                    batches = transactionDetails.forStep(StepState.TARGET_VERIFICATION),
                                    isVial = false,
                                    onBatchImageClick = { imagePath -> onImagePreview(imagePath) },
                                    stringResource(R.string.total_count).uppercase()
                                )
                            }
                        }

                        if (transactionDetails.hasStep(StepState.TARGET_REVERIFICATION)) {
                            SectionBox(title = stringResource(R.string.pill_recount)) {
                                CountSectionContent(
                                    barcodeImage = barcodeImage,
                                    count = transactionDetails.sumForStep(StepState.TARGET_REVERIFICATION),
                                    showFraction = targetCount != null,
                                    targetCount = targetCount,
                                    batches = transactionDetails.forStep(StepState.TARGET_REVERIFICATION),
                                    isVial = false,
                                    onBatchImageClick = { imagePath -> onImagePreview(imagePath) },
                                    stringResource(R.string.total_re_count).uppercase()
                                )
                            }
                        }

                        if (transactionDetails.hasStep(StepState.VIAL)) {
                            SectionBox(title = stringResource(R.string.vial_capture)) {
                                CountSectionContent(
                                    barcodeImage = null,
                                    count = 0,
                                    showFraction = false,
                                    targetCount = null,
                                    batches = transactionDetails.forStep(StepState.VIAL),
                                    isVial = true,
                                    onBatchImageClick = { imagePath -> onImagePreview(imagePath) },
                                    stringResource(R.string.total_re_count).uppercase()
                                )
                            }
                        }

                        if (transactionDetails.hasStep(StepState.CONTAINER_PENDING)) {
                            SectionBox(title = stringResource(R.string.remaining_stock_bottle_count)) {
                                CountSectionContent(
                                    barcodeImage = barcodeImage,
                                    count = transactionDetails.sumForStep(StepState.CONTAINER_PENDING),
                                    showFraction = false,
                                    targetCount = null,
                                    batches = transactionDetails.forStep(StepState.CONTAINER_PENDING),
                                    isVial = false,
                                    onBatchImageClick = { imagePath -> onImagePreview(imagePath) },
                                    stringResource(R.string.total_count).uppercase()
                                )
                            }
                        }

                        SectionBox(title = stringResource(R.string.notes)) {
                            Text(
                                text = note.ifBlank { "—" },
                                color = AppTheme.extendedColors.textColor,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }

                }

            }
        }

    } // outer Box
}

@Composable
private fun SectionBox(
    title: String,
    allowCollapse: Boolean = true,
    defaultExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember {
        mutableStateOf(if (allowCollapse) defaultExpanded else true)
    }

    val chevronDeg by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "chevron"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.extendedColors.primaryBackground, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (allowCollapse) Modifier.clickable { expanded = !expanded }
                    else Modifier
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f)
            )

            if (allowCollapse) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(responsiveDp(22.dp))
                        .rotate(chevronDeg)
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(180))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                content = content
            )
        }
    }
}

@Composable
private fun CountSectionContent(
    barcodeImage: String?,
    count: Int,
    showFraction: Boolean,
    targetCount: Int?,
    batches: List<TxnDetailInfo>,
    isVial: Boolean,
    onBatchImageClick: (String) -> Unit,
    totalCountTitle: String?
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (!isVial) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DrugImageCard(
                    imagePath = barcodeImage,
                    modifier = Modifier
                        .width(responsiveDp(100.dp))
                        .height(responsiveDp(80.dp)),
                    onClick = {
                        barcodeImage?.takeIf { it.isNotBlank() }?.let(onBatchImageClick)
                    }
                )

                Spacer(Modifier.weight(1f))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$count",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (showFraction && targetCount != null) {
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.secondary)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "$targetCount",
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Normal
                        )

                        Spacer(Modifier.height(2.dp))

                        Text(
                            text = totalCountTitle.toString(),
                            color = AppTheme.extendedColors.textColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp
                        )
                    } else {
                        Spacer(Modifier.height(2.dp))

                        Text(
                            text = totalCountTitle.toString(),
                            color = AppTheme.extendedColors.textColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
            }
        }

        if (batches.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                itemsIndexed(
                    items = batches,
                    key = { index, batch -> batch.imagePath ?: "idx-$index" },
                ) { _, batch ->
                    if (isVial) {
                        VialBatchCard(
                            imagePath = batch.imagePath,
                            onClick = {
                                batch.imagePath?.takeIf { it.isNotBlank() }?.let(onBatchImageClick)
                            }
                        )
                    } else {
                        TrayBatchCard(
                            imagePath = batch.imagePath,
                            count = batch.pillCount ?: 0,
                            onClick = {
                                batch.imagePath?.takeIf { it.isNotBlank() }?.let(onBatchImageClick)
                            }
                        )
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.no_batches_recorded),
                color = AppTheme.extendedColors.textColor,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun DrugImageCard(
    imagePath: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val hasImage = !imagePath.isNullOrBlank()

    val painter = if (hasImage) {
        val file = File(imagePath!!)
        rememberAsyncImagePainter(
            model = ImageRequest.Builder(context)
                .data(file)
                .crossfade(true)
                .error(R.drawable.bottle)
                .placeholder(R.drawable.bottle)
                .build()
        )
    } else {
        painterResource(R.drawable.bottle)
    }

    Box(
        modifier = modifier
            .background(
                AppTheme.extendedColors.secondaryBackground,
                RoundedCornerShape(8.dp)
            )
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !imagePath.isNullOrBlank()) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = "Drug image",
            contentScale = if (hasImage) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun TrayBatchCard(
    imagePath: String?,
    count: Int,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .width(responsiveDp(90.dp))
            .height(responsiveDp(60.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !imagePath.isNullOrBlank()) { onClick() }
    ) {
        if (!imagePath.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(imagePath))
                    .crossfade(true)
                    .build(),
                contentDescription = "Batch image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(5.dp)
                .size(28.dp)
                .background(
                    MaterialTheme.colorScheme.primary,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$count",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun VialBatchCard(
    imagePath: String?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .width(responsiveDp(90.dp))
            .height(responsiveDp(60.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !imagePath.isNullOrBlank()) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (!imagePath.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(imagePath))
                    .crossfade(true)
                    .build(),
                contentDescription = "Vial image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(4.dp)
                        .background(
                            Color(0xFFB0BEC5),
                            RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
                        )
                )

                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(32.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF78909C), Color(0xFF455A64))
                            ),
                            RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun KeyValueList(rows: List<Pair<String, String>>) {
    Column {
        rows.forEachIndexed { i, (key, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = key,
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.width(120.dp)
                )

                Text(
                    text = value.ifBlank { "—" },
                    color = AppTheme.extendedColors.textColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (i < rows.lastIndex) {
                HorizontalDivider(
                    color = colorResource(R.color.border_gray).copy(alpha = 0.3f),
                    thickness = 0.5.dp
                )
            }
        }
    }
}

