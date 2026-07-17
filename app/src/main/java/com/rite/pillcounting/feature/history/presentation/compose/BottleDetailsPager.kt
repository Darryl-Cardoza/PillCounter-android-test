package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
import com.rite.pillcounting.core.utils.common.DateFormats
import com.rite.pillcounting.core.utils.common.formatDateToUSFormat
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * One swipeable page per [BottleInfo], showing drug name/NDC (shared across all bottles)
 * plus per-bottle expiry/lot/serial/date/time. Falls back to a single placeholder page
 * with dash values when [bottles] is empty (e.g. legacy transactions with no bottle list).
 */
@Composable
fun BottleDetailsPager(
    drugName: String,
    ndc: String,
    date: String,
    time: String,
    bottles: List<BottleInfo>,
    modifier: Modifier = Modifier,
) {
    val pages = bottles.ifEmpty { listOf(null) }
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val bottle = pages[page]
            KeyValueList(
                rows = listOf(
                    stringResource(R.string.drug_name) to drugName,
                    stringResource(R.string.ndc).uppercase() to ndc,
                    stringResource(R.string.expiry) to (bottle?.expirationDate ?: ""),
                    stringResource(R.string.lotNo) to (bottle?.lotNumber ?: ""),
                    stringResource(R.string.serial_no) to (bottle?.serialNumber ?: ""),
                    stringResource(R.string.date) to formatDateToUSFormat(date, DateFormats.MM_DD_YYYY),
                    stringResource(R.string.time) to time,
                )
            )
        }

        if (pages.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.bottle_page_indicator,
                        pagerState.currentPage + 1,
                        pages.size
                    ),
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(pages.size) { index ->
                    val selected = index == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (selected) 8.dp else 6.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else colorResource(R.color.border_gray),
                                CircleShape
                            )
                    )
                }
            }
        }
    }
}

