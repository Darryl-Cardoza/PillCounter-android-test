package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.FullScreenImageDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.FilledButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.scanning.domain.model.TxnDetail
import com.rite.pillcounting.ui.theme.AppTheme
import java.io.File

@Composable
fun TxnDetailDialog(
    batchNumber: Int,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    details: TxnDetail
) {
    val dimens = AppTheme.dimens
    var showConfirmDeleteDialog by remember { mutableStateOf(false) }
    var showFullScreen by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val cardWidthFraction = if (isLandscape) 0.5f else 0.9f
    val cardHeightFraction = if (isLandscape) 0.9f else 0.4f

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth(cardWidthFraction)
                .fillMaxHeight(cardHeightFraction),
            elevation = CardDefaults.cardElevation(5.dp),
            colors = CardDefaults.cardColors(
                containerColor = AppTheme.extendedColors.secondaryBackground
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${stringResource(R.string.transaction_detail)} $batchNumber",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.extendedColors.textColor
                    )
                    IconButton(onClick = { onDismiss() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = AppTheme.extendedColors.textColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Content row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    val painter = if (!details.image.isNullOrEmpty()) {
                        val file = File(details.image)
                        rememberAsyncImagePainter(
                            ImageRequest.Builder(LocalContext.current)
                                .data(file)
                                .placeholder(R.drawable.bottle)
                                .error(R.drawable.bottle)
                                .build()
                        )
                    } else {
                        painterResource(R.drawable.bottle)
                    }

                    // Tap image to open fullscreen
                    Image(
                        painter = painter,
                        contentDescription = "Captured Image",
                        modifier = Modifier
                            .width(responsiveDp(160.dp))
                            .height(responsiveDp(120.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showFullScreen = true },
                        contentScale = ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(
                        modifier = Modifier.weight(0.6f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.pills_count).uppercase(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Spacer(modifier = Modifier.height(dimens.smallMedium))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(responsiveDp(70.dp))
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary)
                        ) {
                            Text(
                                text = "${details.count}",
                                style = MaterialTheme.typography.titleMedium.copy(color = Color.White),
                                fontSize = responsiveSp(16.sp),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(dimens.smallMedium))
                        Text(
                            text = details.createdAt.toFormattedDate(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = AppTheme.extendedColors.textColor,
                                fontSize = 12.sp
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Buttons
                val buttonSpacing = 25.dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = buttonSpacing * 2),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HollowButton(
                        text = stringResource(R.string.cd_delete).uppercase(),
                        onClick = { showConfirmDeleteDialog = true },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(responsiveDp(buttonSpacing)))
                    FilledButton(
                        text = stringResource(R.string.ok).uppercase(),
                        onClick = onDismiss,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // Fullscreen image preview
    if (showFullScreen && !details.image.isNullOrEmpty()) {
        FullScreenImageDialog(
            imagePath = details.image,
            onDismiss = { showFullScreen = false }
        )
    }

    // Confirm delete
    if (showConfirmDeleteDialog) {
        CommonDialog(
            message = stringResource(R.string.delete_item_text),
            title = "",
            confirmText = stringResource(R.string.delete),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                onDelete()
                showConfirmDeleteDialog = false
            },
            onCancel = { showConfirmDeleteDialog = false }
        )
    }
}
