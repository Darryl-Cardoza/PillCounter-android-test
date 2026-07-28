package com.rite.pillcounting.feature.history.presentation.compose

import android.annotation.SuppressLint
import android.content.res.Configuration
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.LocalConfiguration
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
    serialNo: String = "",
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
    val configuration = LocalConfiguration.current
    val isTabletLandscape = configuration.smallestScreenWidthDp >= 600 &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isTabletLandscape) {
        TabletLandscapeDrugInfo(
            ndc = ndc,
            drugName = drugName,
            expiry = expiry,
            lotNo = lotNo,
            serialNo = serialNo,
            date = date,
            time = time,
            note = note,
            barcodeImage = barcodeImage,
            targetCount = targetCount,
            transactionDetails = transactionDetails,
            drugType = drugType,
            requestedDrugName = requestedDrugName,
            requestedNdc = requestedNdc,
            onImagePreview = onImagePreview,
        )
        return
    }

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

/**
 * Tablet-landscape layout: left column shows Requested/Dispensed drug details + notes,
 * right column shows the QR/vial capture images and the count sections (Initial Container
 * Count, Pill Count, Pill Recount, Remaining Pill Count) gated by which [StepState]s the
 * transaction actually recorded.
 *
 * The Dispensed Drug Details card is a [HorizontalPager] with dot paging so a future
 * multi-record data source (more than one dispensed drug per transaction) can be plugged
 * in without a UI rework; today it always renders exactly one page.
 */
@Composable
private fun TabletLandscapeDrugInfo(
    ndc: String,
    drugName: String,
    expiry: String,
    lotNo: String,
    serialNo: String,
    date: String,
    time: String,
    note: String,
    barcodeImage: String?,
    targetCount: Int?,
    transactionDetails: List<TxnDetailInfo>,
    drugType: String?,
    requestedDrugName: String,
    requestedNdc: String,
    onImagePreview: (String) -> Unit,
) {
    val leftScrollState = rememberScrollState()
    val rightScrollState = rememberScrollState()
    val dispensedPages = remember(drugName, ndc, expiry, lotNo, serialNo, date, time) {
        listOf(
            DispensedRecord(
                drugName = drugName,
                ndc = ndc,
                date = date,
                time = time,
                expiry = expiry,
                lotNo = lotNo,
                serialNo = serialNo,
            )
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.secondaryBackground)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight()
                .verticalScroll(leftScrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionBox(
                title = stringResource(R.string.requested_drug_details),
                allowCollapse = false,
                defaultExpanded = true
            ) {
                KeyValueList(
                    rows = listOf(
                        stringResource(R.string.drug_name) to requestedDrugName.ifBlank { drugName },
                        stringResource(R.string.ndc).uppercase() to requestedNdc.ifBlank { ndc },
                    )
                )
            }

            DispensedDrugDetailsPager(pages = dispensedPages)

            SectionBox(
                title = stringResource(R.string.notes),
                allowCollapse = false,
                defaultExpanded = true
            ) {
                Text(
                    text = note.ifBlank { "—" },
                    color = AppTheme.extendedColors.textColor,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight()
                .verticalScroll(rightScrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionBox(
                    title = stringResource(R.string.container_qr_code),
                    allowCollapse = false,
                    defaultExpanded = true,
                    modifier = Modifier.weight(1f)
                ) {
                    VialBatchCard(
                        imagePath = barcodeImage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(responsiveDp(80.dp)),
                        onClick = {
                            barcodeImage?.takeIf { it.isNotBlank() }?.let(onImagePreview)
                        }
                    )
                }

                val vialImage = transactionDetails.forStep(StepState.VIAL).firstOrNull()?.imagePath
                SectionBox(
                    title = stringResource(R.string.vial_capture),
                    allowCollapse = false,
                    defaultExpanded = true,
                    modifier = Modifier.weight(1f)
                ) {
                    VialBatchCard(
                        imagePath = vialImage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(responsiveDp(80.dp)),
                        onClick = {
                            vialImage?.takeIf { it.isNotBlank() }?.let(onImagePreview)
                        }
                    )
                }
            }

            if (drugType.toString().equals("null")) {
                if (transactionDetails.hasStep(StepState.TARGET_VERIFICATION)) {
                    LandscapeCountSection(
                        title = stringResource(R.string.pill_count),
                        count = transactionDetails.sumForStep(StepState.TARGET_VERIFICATION),
                        targetCount = targetCount,
                        batches = transactionDetails.forStep(StepState.TARGET_VERIFICATION),
                        onBatchImageClick = onImagePreview,
                    )
                }
            } else {
                if (transactionDetails.hasStep(StepState.CONTAINER_INITIATE)) {
                    LandscapeCountSection(
                        title = stringResource(R.string.initial_stock_bottle_count),
                        count = transactionDetails.sumForStep(StepState.CONTAINER_INITIATE),
                        targetCount = null,
                        batches = transactionDetails.forStep(StepState.CONTAINER_INITIATE),
                        onBatchImageClick = onImagePreview,
                    )
                }

                if (transactionDetails.hasStep(StepState.TARGET_VERIFICATION)) {
                    LandscapeCountSection(
                        title = stringResource(R.string.pill_count),
                        count = transactionDetails.sumForStep(StepState.TARGET_VERIFICATION),
                        targetCount = targetCount,
                        batches = transactionDetails.forStep(StepState.TARGET_VERIFICATION),
                        onBatchImageClick = onImagePreview,
                    )
                }

                if (transactionDetails.hasStep(StepState.TARGET_REVERIFICATION)) {
                    LandscapeCountSection(
                        title = stringResource(R.string.pill_recount),
                        count = transactionDetails.sumForStep(StepState.TARGET_REVERIFICATION),
                        targetCount = targetCount,
                        batches = transactionDetails.forStep(StepState.TARGET_REVERIFICATION),
                        onBatchImageClick = onImagePreview,
                    )
                }

                if (transactionDetails.hasStep(StepState.CONTAINER_PENDING)) {
                    LandscapeCountSection(
                        title = stringResource(R.string.remaining_stock_bottle_count),
                        count = transactionDetails.sumForStep(StepState.CONTAINER_PENDING),
                        targetCount = null,
                        batches = transactionDetails.forStep(StepState.CONTAINER_PENDING),
                        onBatchImageClick = onImagePreview,
                    )
                }
            }
        }
    }
}

/**
 * Tablet-landscape count section: total (or count/target) rendered in the header instead of
 * the portrait layout's separate image+number row, with just the per-batch thumbnail strip
 * as the body — matches the design where no standalone drug image is shown per count.
 */
@Composable
private fun LandscapeCountSection(
    title: String,
    count: Int,
    targetCount: Int?,
    batches: List<TxnDetailInfo>,
    onBatchImageClick: (String) -> Unit,
) {
    SectionBox(
        title = title,
        allowCollapse = false,
        defaultExpanded = true,
        headerTrailing = {
            Text(
                text = if (targetCount != null) "$count/$targetCount" else "$count",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    ) {
        if (batches.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                itemsIndexed(
                    items = batches,
                    key = { index, batch -> batch.imagePath ?: "idx-$index" },
                ) { _, batch ->
                    TrayBatchCard(
                        imagePath = batch.imagePath,
                        count = batch.pillCount ?: 0,
                        onClick = {
                            batch.imagePath?.takeIf { it.isNotBlank() }?.let(onBatchImageClick)
                        }
                    )
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

private data class DispensedRecord(
    val drugName: String,
    val ndc: String,
    val date: String,
    val time: String,
    val expiry: String,
    val lotNo: String,
    val serialNo: String,
)

@Composable
private fun DispensedDrugDetailsPager(pages: List<DispensedRecord>) {
    val pagerState = rememberPagerState(pageCount = { pages.size })

    SectionBox(
        title = stringResource(R.string.dispense_drug_details),
        allowCollapse = false,
        defaultExpanded = true,
        headerTrailing = if (pages.size > 1) {
            {
                Text(
                    text = "${pagerState.currentPage + 1} of ${pages.size}",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else null
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = pages.size > 1,
        ) { page ->
            val record = pages[page]
            KeyValueList(
                rows = listOf(
                    stringResource(R.string.dispensed_drug) to record.drugName,
                    stringResource(R.string.ndc).uppercase() to record.ndc,
                    stringResource(R.string.date_and_time) to
                        "${formatDateToUSFormat(record.date, DateFormats.MM_DD_YYYY)} ${record.time}".trim(),
                    stringResource(R.string.expiry) to record.expiry,
                    stringResource(R.string.lotNo) to record.lotNo,
                    stringResource(R.string.serial_no) to record.serialNo,
                )
            )
        }

        if (pages.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(pages.size) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (index == pagerState.currentPage) 8.dp else 6.dp)
                            .background(
                                if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                                else AppTheme.extendedColors.textColor.copy(alpha = 0.3f),
                                CircleShape
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionBox(
    title: String,
    allowCollapse: Boolean = true,
    defaultExpanded: Boolean = false,
    headerTrailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
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
        modifier = modifier
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

            headerTrailing?.invoke()

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
    modifier: Modifier = Modifier
        .width(responsiveDp(90.dp))
        .height(responsiveDp(60.dp)),
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
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

