package com.rite.pillcounting.feature.unsyncedTransaction.presentation.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.countResume.domain.model.CountItem
import com.rite.pillcounting.ui.theme.AppTheme
import java.io.File


@Composable
fun UnsyncedTransactionListCard(
    item: CountItem
) {
    val dimens = AppTheme.dimens

    Card(
        shape = RoundedCornerShape(dimens.small),
        colors = CardDefaults.cardColors(
            containerColor = AppTheme.extendedColors.secondaryBackground
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = dimens.extraSmall
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = dimens.small)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = dimens.small, top = dimens.small, bottom = dimens.small),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Box(
                modifier = Modifier
                    .width(70.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        color = colorResource(R.color.border_gray),
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val hasImage = !item.barcodeImage.isNullOrEmpty()

                val painter = if (hasImage) {
                    val file = File(item.barcodeImage ?: "")
                    rememberAsyncImagePainter(
                        ImageRequest.Builder(LocalContext.current)
                            .data(file)
                            .size(210, 168) // 3x the 70x56dp display box; Coil downsamples on decode
                            .placeholder(R.drawable.bottle)
                            .error(R.drawable.bottle)
                            .build()
                    )
                } else {
                    painterResource(R.drawable.bottle)
                }

                val imageModifier = if (hasImage) {
                    Modifier
                        .fillMaxSize() // full container for placeholder
                        .clip(RoundedCornerShape(8.dp))
                } else {
                    Modifier
                        .size(36.dp) // smaller for cropped image
                        .clip(RoundedCornerShape(8.dp))
                }

                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = if (hasImage) ContentScale.Crop else ContentScale.Fit,
                    modifier = imageModifier
                )
            }


            Spacer(modifier = Modifier.width(12.dp))

            // ---------------- Item Details ----------------
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.extendedColors.textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row {
                    Text(
                        text = item.date,
                        fontSize = 12.sp,
                        color = AppTheme.extendedColors.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (item.isComingFromHL7) {
                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = stringResource(R.string.pms),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

            }

            Spacer(modifier = Modifier.width(5.dp))

            Text(
                text = item.pillCount.toString(),
                fontSize = 16.sp,
                color = AppTheme.extendedColors.textColor,
                maxLines = 1
            )

            Spacer(modifier = Modifier.width(12.dp))

        }
    }
}
