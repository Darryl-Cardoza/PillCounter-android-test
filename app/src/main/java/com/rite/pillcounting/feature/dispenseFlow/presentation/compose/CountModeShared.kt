package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import android.media.Image
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlin.math.roundToInt
import com.rite.pillcounting.R
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDpForCircularCountIndicator
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSp
import com.rite.pillcounting.core.utils.preference.CountCirclePositionPrefs
import java.io.File

/**
 * Shared building blocks for the full-bleed count-mode overlay.
 *
 * The overlay is split by form factor into four entry composables so each layout
 * can be tuned independently:
 *  - [CountModePhonePortrait] / [CountModeTabletPortrait]
 *  - [CountModePhoneLandscape] / [CountModeTabletLandscape]
 *
 * The pieces that are identical across all four (the top details bar and the
 * draggable centre count circle) live here so the variants stay small and only
 * carry their own bottom-bar layout. [InformationPanelSection] routes to the
 * right variant.
 */

/** Translucent background shared by the overlay's bars and the centre circle. */
internal val CountModeBarBackground = Color.Black.copy(alpha = 0.45f)

/**
 * Top translucent details bar: NDC + drug name on the left, Form / Strength /
 * Bucket on the right. Identical in every form factor.
 */
@Composable
internal fun BoxWithConstraintsScope.CountModeTopDetailsBar(
    ndc: String,
    drugName: String,
    strength: String,
    bucket: String,
    drugImage: String? = "",
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CountModeBarBackground)
            // Leave room on the left for the existing back arrow, plus extra
            // breathing space between the arrow and the NDC / drug-name block.
            .padding(start = 76.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!drugImage.isNullOrBlank()) {
            Image(
                painter = rememberAsyncImagePainter(
                    ImageRequest.Builder(LocalContext.current)
                        .data(File(drugImage))
                        .size(300, 225)
                        .placeholder(R.drawable.prescription_icon)
                        .error(R.drawable.prescription_icon)
                        .build()
                ),
                contentDescription = drugName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(80.dp)
                    .height(60.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))
        }
        // Left: NDC + drug name
        Column(modifier = Modifier.weight(1f)) {
            if (ndc.isNotBlank()) {
                Text(
                    text = stringResource(R.string.ndc_value, ndc),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = responsiveSp(8.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = drugName,
                color = Color.White,
                fontSize = responsiveSp(8.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 32.dp)
            )
        }

        // Keep the (ellipsized) drug name from butting up against the Form column.
        Spacer(Modifier.width(16.dp))

        // Right: Strength / Bucket
        if (strength.isNotBlank()) {
            DetailColumn(
                label = stringResource(R.string.detail_label_strength),
                value = strength
            )
            Spacer(Modifier.width(32.dp))
        }
        if (bucket.isNotBlank()) {
            DetailColumn(
                label = stringResource(R.string.detail_label_bucket),
                value = bucket
            )
        }
    }
}

/**
 * The live count circle, centred over the camera feed. The circle IS the action:
 * tapping commits the count ([onAdd]) while counting, or finishes ([onDone]) once
 * a FIXED target is met ([isAllDone]). It can be long-pressed and dragged anywhere
 * over the feed; the position is persisted to prefs and re-clamped on rotation.
 *
 * Identical in every form factor.
 */
@Composable
internal fun BoxWithConstraintsScope.CountModeCenterCircle(
    detectedCount: Int,
    viewModel: PillScanningViewModel,
    isAllDone: Boolean,
    isAddCooldown: Boolean,
    onAdd: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val circleSize = responsiveDpForCircularCountIndicator()
    var circleOffset by remember {
        mutableStateOf(CountCirclePositionPrefs.getOffset(context) ?: Offset.Zero)
    }
    // The offset is stored in raw pixels relative to the container centre, so a
    // position that fit in one orientation can land off-screen after a rotation
    // (different width/height) and the circle "disappears". Clamp the offset to
    // the current viewport whenever the bounds change: keep the user's custom
    // position if it still fits, otherwise snap it back just inside the edge.
    val density = LocalDensity.current
    val maxOffsetX = with(density) { ((maxWidth - circleSize) / 2).toPx() }.coerceAtLeast(0f)
    val maxOffsetY = with(density) { ((maxHeight - circleSize) / 2).toPx() }.coerceAtLeast(0f)
    LaunchedEffect(maxOffsetX, maxOffsetY) {
        val clamped = Offset(
            circleOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
            circleOffset.y.coerceIn(-maxOffsetY, maxOffsetY),
        )
        if (clamped != circleOffset) circleOffset = clamped
    }
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .offset { IntOffset(circleOffset.x.roundToInt(), circleOffset.y.roundToInt()) }
            .size(circleSize)
            .clip(CircleShape)
            // Match the bars' translucency so the circle reads as part of the
            // same overlay.
            .background(CountModeBarBackground)
            // Long-press to pick up, then drag the circle anywhere. The final
            // resting position is saved to prefs so it persists across loads.
            // Key on the current bounds so the gesture detector restarts after a
            // rotation; otherwise the drag lambda keeps the stale max offsets
            // captured at first composition.
            .pointerInput(maxOffsetX, maxOffsetY) {
                detectDragGesturesAfterLongPress(
                    onDrag = { _, dragAmount ->
                        circleOffset = Offset(
                            (circleOffset.x + dragAmount.x).coerceIn(-maxOffsetX, maxOffsetX),
                            (circleOffset.y + dragAmount.y).coerceIn(-maxOffsetY, maxOffsetY),
                        )
                    },
                    onDragEnd = { CountCirclePositionPrefs.setOffset(context, circleOffset) }
                )
            }
            // Whole-circle tap commits the count (Add) while counting, or finishes
            // (Done) once the target is met. Disabled mid-cooldown.
            .clickable(enabled = !isAddCooldown) {
                if (isAllDone) onDone() else onAdd()
            },
        contentAlignment = Alignment.Center
    ) {
        CircularCountIndicator(
            count = detectedCount,
            viewModel = viewModel,
            label = if (isAllDone) {
                stringResource(R.string.pill_scanning_all_done)
            } else {
                stringResource(R.string.pill_scanning_add_this)
            },
            // Target met → play the Ookla-style completion breath + ripple.
            pulsing = isAllDone,
        )
    }
}

/** The tappable "View all counts ›" link shown in the bottom bar. */
@Composable
internal fun ViewAllCountsLink(onClick: () -> Unit) {
    Text(
        text = stringResource(R.string.pill_scanning_view_all_counts) + " ›",
        color = MaterialTheme.colorScheme.primary,
        fontSize = responsiveSp(8.sp),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.clickable { onClick() }
    )
}

/**
 * The progress bar (FIXED counts only) + running count + an optional trailing
 * button. Shared by every bottom-bar layout; the progress bar takes the
 * remaining width via [RowScope.weight] and shrinks to half-width whenever a
 * button is present so the button always has room.
 *
 * The only button is "Proceed", shown for container steps that have no target
 * ([showProceed]); every other step relies on the centre count circle to finish.
 * When present the progress bar shrinks to half-width so the button has room.
 */
@Composable
internal fun RowScope.BottomProgressAndCount(
    isFixed: Boolean,
    totalCount: Int,
    targetCount: Int,
    showProceed: Boolean,
    onProceed: () -> Unit,
    pushCountToEnd: Boolean = false,
) {
    val hasProgress = isFixed && targetCount > 0
    if (hasProgress) {
        val progress = (totalCount.toFloat() / targetCount.toFloat()).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .weight(if (showProceed) 0.5f else 1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.White.copy(alpha = 0.25f),
            // Remove the default gap + stop dot so the bar is continuous with no
            // "cut" between the filled portion and the track.
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
        Spacer(Modifier.width(12.dp))
    } else if (pushCountToEnd) {
        // Portrait only (no inline stepper in this row). A weighted filler
        // positions the count: centred when a Proceed button follows (the trailing
        // half is added after the count below), otherwise pushed to the right edge.
        // Landscape leaves this out — its weighted stepper box already pushes the
        // count + button to the right, so an extra filler here would squeeze the
        // steps to the left and strand the count in dead space.
        Spacer(Modifier.weight(1f))
    }

    Text(
        text = if (isFixed) "$totalCount/$targetCount" else totalCount.toString(),
        color = Color.White,
        fontSize = responsiveSp(10.sp),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false
    )

    if (showProceed) {
        // Portrait centres the count, so balance it with a trailing weighted
        // spacer before the button. Landscape's stepper box handles spacing, so
        // the count sits directly beside the button.
        if (pushCountToEnd && !hasProgress) Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        PillAddButton(
            text = stringResource(R.string.proceed),
            // No count added yet → keep Proceed disabled (greyed out).
            enabled = totalCount > 0,
            onClick = { onProceed() },
        )
    }
}

/** A small label-over-value column used in the top details bar. */
@Composable
internal fun DetailColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = responsiveSp(8.sp),
            maxLines = 1
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = responsiveSp(8.sp),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
