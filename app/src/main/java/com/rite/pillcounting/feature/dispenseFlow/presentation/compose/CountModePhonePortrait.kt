package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSp
import com.rite.pillcounting.core.utils.compose.dosageFormIcon
import java.io.File

/** Step circle diameter for the phone-portrait stepper. */
private val PHONE_PORTRAIT_STEP_SIZE = 36.dp
private val FORM_IMAGE_WIDTH = 64.dp
private const val FORM_IMAGE_ASPECT = 4f / 3f

/**
 * Phone, portrait. Full-bleed count overlay over the camera feed.
 *
 * Layout (top → bottom):
 *  - Top translucent details bar ([CountModePhonePortraitDetailsBar]).
 *  - Centre draggable count circle = the Add / All-Done action (shared).
 *  - Workflow steps floating directly on the feed (no background).
 *  - A single translucent bottom row: "View all counts" | progress | count |
 *    context button (Proceed for container steps, Done once a FIXED target is met).
 *
 * Landscape is intentionally unchanged and lives in [CountModePhoneLandscape].
 */
@Composable
fun CountModePhonePortrait(
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
    bucket: String,
    showHistory: () -> Unit,
    autoRevealCurrentStep: Boolean,
    onAutoRevealed: () -> Unit,
    dosageForm: String = "",
    drugImage: String? = "",
    showGloveIcon: Boolean = false,
    glovesDetected: Boolean = false,
    onReset: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val stepType by viewModel.currentStep.collectAsState()
    val steps by viewModel.steps.collectAsState()
    val isVoiceOverEnabled by viewModel.isSoundEnabled.collectAsState()

    val isFixed = scanType == CountType.FIXED.toString() &&
        stepType != StepState.CONTAINER_INITIATE
    val isAllDone = isFixed && targetCount > 0 && totalCount >= targetCount
    val isRegular = scanType == CountType.REGULAR.toString()
    // Stock count (REGULAR) has no target count, so the centre circle never
    // reaches "All Done" — surface a Proceed button to finish the count instead.
    val showProceed = stepType == StepState.CONTAINER_INITIATE ||
        stepType == StepState.CONTAINER_PENDING ||
        isRegular

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {

        CountModePhonePortraitDetailsBar(
            ndc = ndc,
            drugName = drugName,
            strength = strength,
            bucket = bucket,
            dosageForm = dosageForm,
            drugImage = drugImage,
            showGloveIcon = showGloveIcon,
            glovesDetected = glovesDetected,
        )

        CountModeCenterCircle(
            detectedCount = detectedCount,
            viewModel = viewModel,
            isAllDone = isAllDone,
            isAddCooldown = uiState.isAddCooldown,
            onAdd = onAdd,
            onDone = onDone,
        )

        CountModeBottomStrip(
            steps = steps,
            currentStep = stepType,
            isVoiceOverEnabled = isVoiceOverEnabled,
            circleSize = PHONE_PORTRAIT_STEP_SIZE,
            isLandscape = false,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            isFixed = isFixed,
            totalCount = totalCount,
            targetCount = targetCount,
            onShowHistory = showHistory,
            onProceed = onDone,
            // REGULAR labels the counting step "Scan Open Pills".
            titleOverrides = if (isRegular) {
                mapOf(StepState.TARGET_VERIFICATION to R.string.scan_open_pills)
            } else {
                emptyMap()
            },
            showProceed = showProceed,
            onReset = onReset,
        )
    }
}

/**
 * Phone-portrait top details bar: NDC + drug name stacked above a
 * Form / Strength / Bucket row. The other form factors keep the single-row
 * [CountModeTopDetailsBar].
 */
@Composable
private fun BoxWithConstraintsScope.CountModePhonePortraitDetailsBar(
    ndc: String,
    drugName: String,
    strength: String,
    bucket: String,
    dosageForm: String,
    drugImage: String?,
    showGloveIcon: Boolean,
    glovesDetected: Boolean,
) {
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CountModeBarBackground)
            .padding(start = 36.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (ndc.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.ndc_value, ndc),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = responsiveSp(8.sp, boostOnPhone = true),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = drugName,
                    color = Color.White,
                    fontSize = responsiveSp(9.sp, boostOnPhone = true),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showGloveIcon) {
                Spacer(Modifier.width(12.dp))
                GloveIndicator(glovesDetected = glovesDetected)
            }
        }

        Spacer(Modifier.height(10.dp))

        // The image tile is the tallest child, so it sets the row's height.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            FormColumn(
                drugImage = drugImage,
                dosageForm = dosageForm,
                drugName = drugName,
                modifier = Modifier.weight(1f)
            )
            PortraitDetailColumn(
                label = stringResource(R.string.detail_label_strength),
                value = strength,
                modifier = Modifier.weight(1f)
            )
            PortraitDetailColumn(
                label = stringResource(R.string.detail_label_bucket),
                value = bucket,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * The Form column: the downloaded drug image on its own when one is on disk,
 * otherwise the "Form" label above the dosage-form icon.
 */
@Composable
private fun FormColumn(
    drugImage: String?,
    dosageForm: String,
    drugName: String,
    modifier: Modifier = Modifier
) {
    val imageFile = remember(drugImage) {
        drugImage?.takeIf { it.isNotBlank() }?.let(::File)
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (imageFile != null) {
            DrugImageTile(
                imageFile = imageFile,
                drugName = drugName,
                modifier = Modifier
                    .width(responsiveDp(FORM_IMAGE_WIDTH))
                    .aspectRatio(FORM_IMAGE_ASPECT)
            )
        } else {
            PortraitDetailLabel(stringResource(R.string.detail_label_form))
            Spacer(Modifier.height(2.dp))
            Icon(
                painter = painterResource(dosageFormIcon(dosageForm)),
                contentDescription = dosageForm,
                tint = Color.White,
                modifier = Modifier.size(responsiveDp(20.dp))
            )
        }
    }
}

@Composable
private fun PortraitDetailLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.7f),
        fontSize = responsiveSp(9.sp, boostOnPhone = true),
        maxLines = 1
    )
}

/** A labelled value in the bar's bottom row. Blank values show an em dash. */
@Composable
private fun PortraitDetailColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PortraitDetailLabel(label)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value.ifBlank { "—" },
            color = Color.White,
            fontSize = responsiveSp(9.sp, boostOnPhone = true),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
