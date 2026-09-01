package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSp
import com.rite.pillcounting.core.utils.constants.Dimens
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun CameraActionBar(
    onRedo: () -> Unit,
    onCapture: () -> Unit,
    onDone: () -> Unit,
    viewModel: PillScanningViewModel
) {
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            ActionButtons(onRedo, onCapture, onDone, viewModel)

        }

    } else {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {

            ActionButtons(onRedo, onCapture, onDone, viewModel)

        }
    }
}

@Composable
private fun ActionButtons(
    onRedo: () -> Unit,
    onCapture: () -> Unit,
    onDone: () -> Unit,
    viewModel: PillScanningViewModel
) {
    val capturedBitmap by viewModel.capturedBitmap.collectAsState()
    val hasCapture = capturedBitmap != null
    val isCapturing by viewModel.isCapturing.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dimens = AppTheme.dimens
    val spacing = dimens.xxxLarge

    if (isLandscape) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButtonItems(hasCapture, isCapturing, onRedo, onCapture, onDone, dimens)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButtonItems(hasCapture, isCapturing, onRedo, onCapture, onDone, dimens)
        }
    }
}

@Composable
private fun ActionButtonItems(
    hasCapture: Boolean,
    isCapturing: Boolean,
    onRedo: () -> Unit,
    onCapture: () -> Unit,
    onDone: () -> Unit,
    dimens: Dimens
) {
    // REDO
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.clickable(enabled = hasCapture) { onRedo() }
    ) {
        Icon(
            painter =  painterResource(id = R.drawable.captureimageredoicon),
            contentDescription = "Redo",
            tint = if (hasCapture) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(responsiveDp(36.dp))
        )
        Text(
            text = stringResource(R.string.redo),
            color = if (hasCapture) AppTheme.extendedColors.textColor else Color.White.copy(alpha = 0.5f),
            fontSize = responsiveSp(12.sp),
        )
    }

    // CAMERA BUTTON
    Box(
        modifier = Modifier
            .size(responsiveDp(72.dp))
            .background(
                // Also greyed while a capture is in flight — the tap is already
                // inert, this is what tells the user to stop tapping.
                color = if (hasCapture || isCapturing) AppTheme.extendedColors.primaryBackground else MaterialTheme.colorScheme.primary,
                shape = CircleShape
            )
            .clickable(enabled = !hasCapture && !isCapturing) { onCapture() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter =  painterResource(id = R.drawable.capturevialimage),
            contentDescription = "Capture",
            tint = Color.White,
            modifier = Modifier.size(responsiveDp(36.dp))
        )
    }

    // DONE
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = hasCapture) { onDone() }
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "Done",
            tint = if (hasCapture) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(responsiveDp(36.dp))
        )
        Text(
            text = stringResource(R.string.done),
            color = if (hasCapture) AppTheme.extendedColors.textColor else Color.White.copy(alpha = 0.5f),
            fontSize = responsiveSp(12.sp)
        )
    }
}