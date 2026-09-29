package com.dispensesure.retail.feature.dashboard.presentation.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveSp
import com.dispensesure.retail.core.utils.compose.cardSelectionShadow
import com.dispensesure.retail.feature.dashboard.presentation.model.KpiCardItem
import com.dispensesure.retail.feature.dashboard.presentation.model.KpiSectionState
import com.dispensesure.retail.feature.dashboard.presentation.model.toCardItems
import com.dispensesure.retail.ui.theme.AppTheme.extendedColors

// Landscape: all KPIs stacked in one column; scrolls when the screen is short.
@Composable
internal fun LandscapeKpiList(
    state: KpiSectionState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val cards = state.toCardItems()
    Column(
        // Bottom padding keeps the last card's shadow from being clipped by the scroll.
        modifier = modifier.verticalScroll(rememberScrollState()).padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(responsiveDp(DashboardBaseSizes.SectionGap)),
    ) {
        cards.forEach { card ->
            LandscapeKpiCard(
                card = card,
                compact = compact,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = responsiveDp(DashboardBaseSizes.LandscapeKpiCardMinHeight))
            )
        }
    }
}

// Portrait: KPIs side by side. Tablet shares the width; phone scrolls sideways.
@Composable
internal fun PortraitKpiRow(
    state: KpiSectionState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val cards = state.toCardItems()
    val cardGap = Arrangement.spacedBy(responsiveDp(DashboardBaseSizes.CardGap))
    if (compact) {
        val tileWidth = responsiveDp(DashboardBaseSizes.PortraitKpiTileWidthOnPhone)
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = cardGap,
        ) {
            cards.forEach { card ->
                PortraitKpiCard(card = card, modifier = Modifier.width(tileWidth).fillMaxHeight())
            }
        }
    } else {
        Row(modifier = modifier, horizontalArrangement = cardGap) {
            cards.forEach { card ->
                PortraitKpiCard(card = card, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

// Icon left, title/subtitle middle, count right. Phone puts title and subtitle on one line.
@Composable
internal fun LandscapeKpiCard(
    card: KpiCardItem,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    SelectableKpiCard(card = card, modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KpiIcon(iconRes = card.iconRes)
            Spacer(modifier = Modifier.width(responsiveDp(DashboardBaseSizes.IconToTextGap)))
            if (compact) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KpiTitle(titleRes = card.titleRes, modifier = Modifier.weight(1f, fill = false))
                    Spacer(modifier = Modifier.width(responsiveDp(DashboardBaseSizes.TitleToSubtitleGap)))
                    KpiSubtitle(subtitleRes = card.subtitleRes)
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    KpiTitle(titleRes = card.titleRes)
                    KpiSubtitle(subtitleRes = card.subtitleRes)
                }
            }
            Spacer(modifier = Modifier.width(responsiveDp(DashboardBaseSizes.IconToTextGap)))
            KpiCount(count = card.count)
        }
    }
}

// Icon, title, subtitle and count stacked and centered.
@Composable
internal fun PortraitKpiCard(
    card: KpiCardItem,
    modifier: Modifier = Modifier,
) {
    SelectableKpiCard(card = card, modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KpiIcon(iconRes = card.iconRes)
            KpiTitle(titleRes = card.titleRes, textAlign = TextAlign.Center)
            KpiSubtitle(subtitleRes = card.subtitleRes, textAlign = TextAlign.Center)
            KpiCount(count = card.count)
        }
    }
}

// Shared card shell: resting drop shadow, primary side border when selected, faded when disabled.
// Background never changes, so deselecting fully reverts the look.
@Composable
private fun SelectableKpiCard(
    card: KpiCardItem,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(DashboardBaseSizes.KpiCardCornerRadius)
    val primary = MaterialTheme.colorScheme.primary
    Card(
        modifier = modifier
            // Always inactive: we only want its resting drop shadow. Selection is the side border below.
            .cardSelectionShadow(
                isActive = false,
                selectionColor = primary,
                cornerRadius = DashboardBaseSizes.KpiCardCornerRadius,
            )
            .then(
                if (card.isSelected) {
                    Modifier.sideBorder(
                        color = primary,
                        width = DashboardBaseSizes.SelectedBorderWidth,
                        cornerRadius = DashboardBaseSizes.KpiCardCornerRadius,
                    )
                } else {
                    Modifier
                },
            )
            .semantics { if (card.isDisabled) disabled() }
            .clickable(onClick = card.onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = extendedColors.secondaryBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(responsiveDp(DashboardBaseSizes.CardInnerPadding))
                .then(if (card.isDisabled) Modifier.alpha(0.5f) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

// Primary stroke on the left and right edges, curving into the four corners.
// Drawn over the card; the middle of the top and bottom edges stays open.
private fun Modifier.sideBorder(color: Color, width: Dp, cornerRadius: Dp): Modifier = drawWithContent {
    drawContent()
    val strokeWidth = width.toPx()
    val inset = strokeWidth / 2
    val radius = cornerRadius.toPx()
    listOf(0f, size.width - radius).forEach { stripLeft ->
        clipRect(left = stripLeft, top = 0f, right = stripLeft + radius, bottom = size.height) {
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius(radius - inset),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

@Composable
private fun KpiIcon(@DrawableRes iconRes: Int) {
    Icon(
        painter = painterResource(id = iconRes),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.size(responsiveDp(DashboardBaseSizes.KpiIconSize)),
    )
}

@Composable
private fun KpiTitle(
    @StringRes titleRes: Int,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = stringResource(titleRes),
        fontSize = responsiveSp(DashboardBaseSizes.KpiTitleText),
        color = extendedColors.textColor,
        fontWeight = FontWeight.SemiBold,
        textAlign = textAlign,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun KpiSubtitle(@StringRes subtitleRes: Int, textAlign: TextAlign = TextAlign.Start) {
    Text(
        text = stringResource(subtitleRes),
        fontSize = responsiveSp(DashboardBaseSizes.KpiSubtitleText),
        color = extendedColors.textColor,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun KpiCount(count: Int) {
    Text(
        text = count.toString(),
        fontSize = responsiveSp(DashboardBaseSizes.KpiCountText),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}
