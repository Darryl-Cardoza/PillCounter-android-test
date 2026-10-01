package com.dispensesure.retail.feature.dashboard.presentation.compose

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Base sizes for the dashboard. Text goes through responsiveSp, boxes through
// responsiveDp — never use these raw, except the "fixed" group at the bottom.
internal object DashboardBaseSizes {
    // Text (responsiveSp)
    val SectionLabelText = 8.sp
    val QuickActionTitleText = 10.sp
    val QuickActionSubtitleText = 7.sp
    val KpiTitleText = 8.sp
    val KpiSubtitleText = 7.sp
    val KpiCountText = 12.sp
    val UpNextLabelText = 7.sp
    val UpNextDrugNameText = 22.sp
    val UpNextDetailText = 9.sp
    val UpNextCountText = 18.sp

    // Boxes (responsiveDp)
    val ScreenEdgePadding = 12.dp
    val SectionGap = 8.dp
    val CardGap = 4.dp
    val CardInnerPadding = 8.dp
    val KpiIconSize = 20.dp
    val QuickActionRingSize = 44.dp
    val QuickActionIconSize = 34.dp
    val IconToTextGap = 8.dp
    val TitleToSubtitleGap = 2.dp
    val PortraitKpiTileWidthOnPhone = 110.dp
    val UpNextCardPadding = 14.dp
    val UpNextThumbnailWidth = 96.dp
    val UpNextThumbnailHeight = 72.dp
    val UpNextHeaderIconSize = 20.dp
    val UpNextHeaderIconToLabelGap = 2.dp
    val UpNextTextLineGap = 4.dp

    // Fixed on purpose: scaling these makes tablet cards look heavy.
    val KpiCardCornerRadius = 8.dp
    val QuickActionCardCornerRadius = 12.dp
    val UpNextCardCornerRadius = 12.dp
    val SelectedBorderWidth = 1.5.dp
    val RingBorderWidth = 2.dp

    // Card height
    val LandscapeKpiCardMinHeight = 22.dp
}
