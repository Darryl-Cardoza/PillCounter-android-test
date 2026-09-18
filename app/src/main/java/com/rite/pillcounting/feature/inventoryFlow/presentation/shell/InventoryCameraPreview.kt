package com.rite.pillcounting.feature.inventoryFlow.presentation.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.scanning.presentation.compose.CameraPreviewSection
import java.util.concurrent.atomic.AtomicLong

/**
 * Live CameraX preview for inventory mode, identical across all form factors.
 * Each frame is forwarded to the host's barcode analyzer, NOT to the ML pill
 * detector. We own the [androidx.camera.core.ImageProxy] here: the analyzer
 * snapshots a Bitmap and never closes the proxy, so without the explicit close
 * CameraX's frame pool fills after the first scan and emission stops (symptom:
 * only the first NDC is ever detected). Falls back to a permission prompt until
 * the camera is granted. [frameTag] only labels the throttled frame log.
 */
@Composable
fun InventoryScanScope.CameraPreview(
    frameTag: String,
    modifier: Modifier = Modifier,
) {
    val cameraUiState by cameraVm.uiState.collectAsState()
    val frameCounter = remember { AtomicLong(0L) }

    Box(modifier = modifier.background(Color.Black)) {
        if (hasCameraPermission) {
            CameraPreviewSection(
                viewModel = cameraVm,
                pills = cameraUiState.detectedPills,
                isCameraPaused = false,
                imageFrameWidth = cameraUiState.imageFrameWidth,
                imageFrameHeight = cameraUiState.imageFrameHeight,
                onFrame = { imageProxy ->
                    val n = frameCounter.incrementAndGet()
                    if (n % 30 == 0L) logger.d("INV_SCAN($frameTag) onFrame tick=$n")
                    try {
                        analyzer.analyze(imageProxy) { raw, _ -> onBarcode(raw) }
                    } finally {
                        imageProxy.close()
                    }
                },
                onFilteredCountChanged = { /* no-op in inventory mode */ },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.camera_permission_required),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}
