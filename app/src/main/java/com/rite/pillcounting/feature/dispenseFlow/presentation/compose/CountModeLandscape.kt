package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDpForCircularCountIndicator
import com.rite.pillcounting.core.utils.compose.WorkflowStepper
import com.rite.pillcounting.core.utils.preference.CountCirclePositionPrefs

/**
 * Shared full-bleed count-mode overlay (phone + tablet, BOTH orientations).
 *
 * Despite the historical name, this drives portrait and landscape alike — it is
 * the single overlay used for every pill-counting step (VIAL keeps the separate
 * CameraActionBar strip). [InformationPanelSection] routes both orientations here.
 *
 * Full-bleed overlay over the camera feed:
 *  - Top: translucent details bar (NDC + drug name on the left; Form / Strength /
 *    Bucket on the right).
 *  - Center: the live [CircularCountIndicator] is itself the action — tapping it
 *    commits the count ([onAdd]). Once a FIXED count reaches its target the circle
 *    becomes the "All Done" action (tap = [onDone]). There is no separate Add button.
 *  - Bottom: translucent progress bar showing counted / target with a
 *    "View all counts" affordance.
 *
 * Feature/behaviour is unchanged — this only restyles the existing actions.
 */
@Composable
fun CountModeLandscape(
    totalCount: Int,
    targetCount: Int,
    scanType: String,
    detectedCount: Int,
    onAdd: () -> Unit,
    onDone: () -> Unit,
    viewModel: PillScanningViewModel,
    drugName: String,
    ndc: String,
    strength: String,
    dosageForm: String,
    bucket: String,
    showHistory: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val stepType by viewModel.currentStep.collectAsState()
    val steps by viewModel.steps.collectAsState()

    if (stepType == StepState.VIAL) {
        viewModel.pausePillDetection()
        CameraActionBar(
            onRedo = { viewModel.redoCaptureImage() },
            onCapture = { viewModel.captureImage() },
            onDone = { viewModel.saveCaptureImage() },
            viewModel = viewModel
        )
        return
    }

    val isFixed = scanType == CountType.FIXED.toString() &&
        stepType != StepState.CONTAINER_INITIATE
    // Target reached → the centre circle turns into the "All Done" action.
    val isAllDone = isFixed && targetCount > 0 && totalCount >= targetCount

    val barBackground = Color.Black.copy(alpha = 0.45f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {

        // ── TOP: details bar ──────────────────────────────────────────────
        // Rounded, inset card (matches the Figma): margin from the screen edges
        // and rounded corners, translucent over the camera feed.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(barBackground)
                // Leave room on the left for the existing back arrow, plus extra
                // breathing space between the arrow and the NDC / drug-name block.
                .padding(start = 76.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: NDC + drug name
            Column(modifier = Modifier.weight(1f)) {
                if (ndc.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.ndc_value, ndc),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = drugName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Right: Form (icon) / Strength / Bucket
            if (dosageForm.isNotBlank()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.detail_label_form),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Icon(
                        painter = painterResource(R.drawable.pill_capsule),
                        contentDescription = dosageForm,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(24.dp))
            }
            if (strength.isNotBlank()) {
                DetailColumn(
                    label = stringResource(R.string.detail_label_strength),
                    value = strength
                )
                Spacer(Modifier.width(24.dp))
            }
            if (bucket.isNotBlank()) {
                DetailColumn(
                    label = stringResource(R.string.detail_label_bucket),
                    value = bucket
                )
            }
        }

        // ── CENTER: count circle (it IS the action) ───────────────────────
        // The "All Done" label is always visible inside the circle and is its own
        // tap target (= finish the count). Tapping the rest of the circle commits
        // the current count (Add). Once the target is met the circle shows only
        // "All Done".
        //
        // The circle is draggable: a long-press picks it up, then the user can
        // drag it anywhere over the camera feed for their convenience. The offset
        // lives in [circleOffset] which is seeded from SharedPreferences: if the
        // user has moved it before we restore that position, otherwise it stays at
        // centre (Offset.Zero). The new position is persisted when a drag ends.
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
                // Match the top/bottom bars' translucency so the circle reads as
                // part of the same overlay.
                .background(barBackground)
                // Long-press to pick up, then drag the circle anywhere. The final
                // resting position is saved to prefs so it persists across loads.
                // Key on the current bounds so the gesture detector restarts after
                // a rotation; otherwise the drag lambda keeps the stale max offsets
                // captured at first composition and the circle can't be dragged past
                // the *previous* orientation's limits (e.g. only mid-screen after
                // landscape → portrait).
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
                // Whole-circle tap commits the count (Add) while counting, or
                // finishes (Done) once the target is met. Disabled mid-cooldown.
                .clickable(enabled = !uiState.isAddCooldown) {
                    if (isAllDone) onDone() else onAdd()
                },
            contentAlignment = Alignment.Center
        ) {
            // Both states keep the same ring (CircularCountIndicator); only the
            // caption under the count changes: "ADD THIS" while counting, "ALL DONE"
            // once the target is reached.
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

        // ── BOTTOM: View all counts ─ progress bar ─ count ────────────────
        // Single row: the "View all counts" affordance on the left, then the
        // progress bar filling the middle, then the running count on the right.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(barBackground)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.pill_scanning_view_all_counts) + " ›",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clickable { showHistory() }
            )

            Spacer(Modifier.width(12.dp))

            // Workflow steps live inside the bar (between "View all counts" and the
            // progress bar). Weighted so on narrow widths (phone portrait) it shares
            // the leftover space with the progress bar instead of taking a fixed
            // footprint that pushes the progress bar to zero and the count text off
            // the right edge. The stepper fills the width it's given; the weight
            // bounds that, so nothing overflows.
            Box(modifier = Modifier.weight(1f)) {
                WorkflowStepper(
                    steps = steps,
                    currentStep = stepType,
                )
            }

            Spacer(Modifier.width(12.dp))

            if (isFixed && targetCount > 0) {
                val progress = (totalCount.toFloat() / targetCount.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    // Remove the default gap + stop dot so the bar is continuous
                    // with no "cut" between the filled portion and the track.
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
                Spacer(Modifier.width(12.dp))
            }

            Text(
                text = if (isFixed) "$totalCount/$targetCount" else totalCount.toString(),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** A small label-over-value column used in the top details bar. */
@Composable
private fun DetailColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            maxLines = 1
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
