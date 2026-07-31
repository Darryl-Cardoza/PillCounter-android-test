package com.rite.pillcounting.feature.history.presentation.compose

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.dtos.TxnDetailInfo
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
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
    isSubstitute: Boolean = false,
    requestedDrugName: String = "",
    requestedNdc: String = "",
    bottleList: List<BottleInfo> = emptyList(),
    onImagePreview: (String) -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val isTabletDevice = configuration.smallestScreenWidthDp >= 600
    val isDeviceLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    DrugDetailLayout(
        landscape = isDeviceLandscape,
        collapsible = !isTabletDevice,
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
        isSubstitute = isSubstitute,
        requestedDrugName = requestedDrugName,
        requestedNdc = requestedNdc,
        bottleList = bottleList,
        onImagePreview = onImagePreview,
    )
}

/**
 * Drug-detail layout used on every device/orientation: a "left" block with Requested/
 * Dispensed drug details + notes, and a "right" block with the QR/vial capture images and
 * the count sections (Initial Container Count, Pill Count, Pill Recount, Remaining Pill
 * Count) gated by which [StepState]s the transaction actually recorded. In landscape the two
 * blocks sit side by side (50/50 width); in portrait they stack, left block first, right
 * block below. On tablet every section stays fully expanded ([collapsible] = false); on
 * phone every section is collapsible except the Container QR Code / Dispensed Vial pair,
 * which always stays expanded and side by side.
 *
 * The Dispensed Drug Details card is a [HorizontalPager] with dot paging — one page per
 * scanned bottle (from [bottleList]), so a multi-bottle dispense pages through each bottle's
 * own expiry/lot/serial.
 */
@Composable
private fun DrugDetailLayout(
    landscape: Boolean,
    collapsible: Boolean,
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
    isSubstitute: Boolean,
    requestedDrugName: String,
    requestedNdc: String,
    bottleList: List<BottleInfo>,
    onImagePreview: (String) -> Unit,
) {
    val dispensedPages = remember(bottleList, drugName, ndc, expiry, lotNo, serialNo, date, time) {
        if (bottleList.isNotEmpty()) {
            bottleList.map { bottle ->
                DispensedRecord(
                    drugName = drugName,
                    ndc = ndc,
                    date = date,
                    time = time,
                    expiry = bottle.expirationDate.orEmpty(),
                    lotNo = bottle.lotNumber.orEmpty(),
                    serialNo = bottle.serialNumber.orEmpty(),
                )
            }
        } else {
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
    }
    val barcodeImages = remember(bottleList, barcodeImage) {
        bottleList.mapNotNull { it.barcodeImagePath?.takeIf(String::isNotBlank) }
            .ifEmpty { listOfNotNull(barcodeImage?.takeIf(String::isNotBlank)) }
    }
    val vialImage = transactionDetails.forStep(StepState.VIAL).firstOrNull()?.imagePath

    if (landscape) {
        val leftScrollState = rememberScrollState()
        val rightScrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.extendedColors.secondaryBackground)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxHeight()
                    .verticalScroll(leftScrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TabletDrugInfoLeftContent(
                    isSubstitute, requestedDrugName, requestedNdc, drugName, ndc, dispensedPages, note, collapsible
                )
            }

            Column(
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxHeight()
                    .verticalScroll(rightScrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TabletDrugInfoRightContent(
                    barcodeImages, vialImage, drugType, transactionDetails, targetCount, onImagePreview, collapsible
                )
            }
        }
    } else {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.extendedColors.secondaryBackground)
                .verticalScroll(scrollState)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TabletDrugInfoLeftContent(
                isSubstitute, requestedDrugName, requestedNdc, drugName, ndc, dispensedPages, note, collapsible
            )
            TabletDrugInfoRightContent(
                barcodeImages, vialImage, drugType, transactionDetails, targetCount, onImagePreview, collapsible
            )
        }
    }
}

@Composable
private fun TabletDrugInfoLeftContent(
    isSubstitute: Boolean,
    requestedDrugName: String,
    requestedNdc: String,
    drugName: String,
    ndc: String,
    dispensedPages: List<DispensedRecord>,
    note: String,
    collapsible: Boolean = false,
) {
    SectionBox(
        title = stringResource(R.string.requested_drug_details),
        allowCollapse = collapsible,
        defaultExpanded = true,
        spacious = true,
    ) {
        KeyValueList(
            rows = listOf(
                stringResource(R.string.drug_name) to requestedDrugName.ifBlank { drugName },
                stringResource(R.string.ndc).uppercase() to requestedNdc.ifBlank { ndc },
            )
        )
    }

    DispensedDrugDetailsPager(pages = dispensedPages, isSubstitute = isSubstitute, collapsible = collapsible)

    SectionBox(
        title = stringResource(R.string.notes),
        allowCollapse = collapsible,
        defaultExpanded = true,
        spacious = true,
    ) {
        Text(
            text = note.ifBlank { "—" },
            color = AppTheme.extendedColors.textColor,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun TabletDrugInfoRightContent(
    barcodeImages: List<String?>,
    vialImage: String?,
    drugType: String?,
    transactionDetails: List<TxnDetailInfo>,
    targetCount: Int?,
    onImagePreview: (String) -> Unit,
    collapsible: Boolean = false,
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val images = barcodeImages.ifEmpty { listOf(null) }
                itemsIndexed(images, key = { index, path -> path ?: "idx-$index" }) { _, path ->
                    LandscapeSquareImage(
                        imagePath = path,
                        onClick = { path?.takeIf { it.isNotBlank() }?.let(onImagePreview) }
                    )
                }
            }
        }

        SectionBox(
            title = stringResource(R.string.vial_capture),
            allowCollapse = false,
            defaultExpanded = true,
            modifier = Modifier.weight(1f)
        ) {
            LandscapeSquareImage(
                imagePath = vialImage,
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
                collapsible = collapsible,
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
                collapsible = collapsible,
            )
        }

        if (transactionDetails.hasStep(StepState.TARGET_VERIFICATION)) {
            LandscapeCountSection(
                title = stringResource(R.string.pill_count),
                count = transactionDetails.sumForStep(StepState.TARGET_VERIFICATION),
                targetCount = targetCount,
                batches = transactionDetails.forStep(StepState.TARGET_VERIFICATION),
                onBatchImageClick = onImagePreview,
                collapsible = collapsible,
            )
        }

        if (transactionDetails.hasStep(StepState.TARGET_REVERIFICATION)) {
            LandscapeCountSection(
                title = stringResource(R.string.pill_recount),
                count = transactionDetails.sumForStep(StepState.TARGET_REVERIFICATION),
                targetCount = targetCount,
                batches = transactionDetails.forStep(StepState.TARGET_REVERIFICATION),
                onBatchImageClick = onImagePreview,
                collapsible = collapsible,
            )
        }

        if (transactionDetails.hasStep(StepState.CONTAINER_PENDING)) {
            LandscapeCountSection(
                title = stringResource(R.string.remaining_stock_bottle_count),
                count = transactionDetails.sumForStep(StepState.CONTAINER_PENDING),
                targetCount = null,
                batches = transactionDetails.forStep(StepState.CONTAINER_PENDING),
                onBatchImageClick = onImagePreview,
                collapsible = collapsible,
            )
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
    collapsible: Boolean = false,
) {
    SectionBox(
        title = title,
        allowCollapse = collapsible,
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
                    LandscapeSquareImage(
                        imagePath = batch.imagePath,
                        badgeCount = batch.pillCount ?: 0,
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

/**
 * Square thumbnail used for every image tile in the tablet-landscape layout (container QR
 * code, dispensed vial, per-step pill-count photos) so all images share one size/shape.
 */
@Composable
private fun LandscapeSquareImage(
    imagePath: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(responsiveDp(84.dp)),
    badgeCount: Int? = null,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AppTheme.extendedColors.secondaryBackground)
            .clickable(enabled = !imagePath.isNullOrBlank()) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (!imagePath.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(imagePath))
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (badgeCount != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$badgeCount",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
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
private fun DispensedDrugDetailsPager(
    pages: List<DispensedRecord>,
    isSubstitute: Boolean = false,
    collapsible: Boolean = false,
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })

    SectionBox(
        title = if (isSubstitute) stringResource(R.string.substitute_drug_details)
        else stringResource(R.string.dispense_drug_details),
        allowCollapse = collapsible,
        defaultExpanded = true,
        spacious = true,
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
                    val selected = index == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (selected) 18.dp else 6.dp, height = 6.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else colorResource(R.color.border_gray),
                                RoundedCornerShape(3.dp)
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
    spacious: Boolean = false,
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

    val headerHorizontalPadding = if (spacious) 18.dp else 14.dp
    val headerVerticalPadding = if (spacious) 16.dp else 13.dp
    val contentBottomPadding = if (spacious) 20.dp else 14.dp

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
                .padding(horizontal = headerHorizontalPadding, vertical = headerVerticalPadding),
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
                    .padding(start = headerHorizontalPadding, end = headerHorizontalPadding, bottom = contentBottomPadding),
                content = content
            )
        }
    }
}

@Composable
internal fun KeyValueList(rows: List<Pair<String, String>>) {
    Column {
        rows.forEachIndexed { i, (key, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
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

