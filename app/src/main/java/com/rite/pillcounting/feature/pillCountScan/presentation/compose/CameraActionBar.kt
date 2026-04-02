package com.rite.pillcounting.feature.pillCountScan.presentation.compose

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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel.PillScanningViewModel

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
                .padding(start = 55.dp),
            verticalArrangement = Arrangement.SpaceAround,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            ActionButtons(onRedo, onCapture, onDone,viewModel)

        }

    } else {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 45.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {

            ActionButtons(onRedo, onCapture, onDone,viewModel)

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
    val capturedBitmap    by viewModel.capturedBitmap.collectAsState()
    // REDO
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onRedo() }
    ) {

        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Redo",
            tint = Color.Cyan,
            modifier = Modifier.size(32.dp)
        )

        Text(
            text = stringResource(R.string.redo),
            color = Color.White,
            fontSize = 12.sp
        )
    }

    // CAMERA BUTTON
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(Color.Cyan, CircleShape)
            .clickable(
                enabled = capturedBitmap == null
            ) {
                onCapture()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = "Capture",
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }

    // DONE
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onDone() }
    ) {

        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "Done",
            tint = Color.Cyan,
            modifier = Modifier.size(32.dp)
        )

        Text(
            text = stringResource(R.string.done),
            color = Color.White,
            fontSize = 12.sp
        )
    }
}
