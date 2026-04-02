package com.rite.pillcounting.feature.pillCountScan.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveButtonHeight
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDpForCircularCountProgressPortrait
import com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun CountModePortrait(
    totalCount: Int,
    targetCount: Int,
    scanType: String,
    detectedCount: Int,
    onAdd: () -> Unit,
    onDone: () -> Unit,
    viewModel: PillScanningViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val stepType by viewModel.currentStep.collectAsState()
    if (stepType != StepState.VIAL) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {

            // -------- LEFT: Total + Target --------
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(top = 10.dp, bottom = 15.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {

                    // Total Count
                    Text(
                        text = totalCount.toString(),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium
                    )

                    if (scanType == CountType.FIXED.toString() && stepType != StepState.CONTAINER_INITIATE) {
                        Box(
                            modifier = Modifier
                                .width(48.dp)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = targetCount.toString(),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Spacer(modifier = Modifier.height(responsiveDp(25.dp)))
                    }

                    Text(
                        text = stringResource(R.string.pill_scanning_total_count),
                        color = AppTheme.extendedColors.textColor,
                        fontSize = 15.sp
                    )

                }
            }

            // -------- CENTER: Circle + Add (merged) --------

            Box(
                modifier = Modifier
                    .height(responsiveDpForCircularCountProgressPortrait(150.dp))
                    .weight(1f),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .size(responsiveDpForCircularCountProgressPortrait(130.dp))
                        .align(Alignment.TopCenter),
                    contentAlignment = Alignment.Center
                ) {
                    CircularCountIndicator(
                        count = detectedCount,
                        viewModel = viewModel
                    )
                }

                ActionButtonPrimary(
                    text = if (uiState.isAddCooldown)
                        stringResource(R.string.pill_scanning_wait_button)
                    else
                        stringResource(R.string.pill_scanning_add_button),

                    onClick = { onAdd() },

                    enabled = !uiState.isAddCooldown,

                    color = if (uiState.isAddCooldown)
                        Color.Gray
                    else
                        MaterialTheme.colorScheme.primary,

                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-6).dp)
                        .height(responsiveButtonHeight(40.dp))
                        .padding(horizontal = 14.dp),

                    width = 80,
                    fontSize = 15
                )
            }
//        }

            // -------- RIGHT: Done --------
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(top = 18.dp, end = 8.dp, bottom = 15.dp)
                    .clickable { onDone() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.all_done),
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(responsiveDp(25.dp)))
                Text(
                    text = stringResource(R.string.pill_scanning_all_done),
                    color = AppTheme.extendedColors.textColor,
                    fontSize = 15.sp
                )
            }
        }
    } else {
        CameraActionBar(

            onRedo = {
                viewModel.redoCaptureImage()
            },

            onCapture = {
                viewModel.captureImage()
            },

            onDone = {
                viewModel.saveCaptureImage()
            },
            viewModel=viewModel
        )
    }
}
