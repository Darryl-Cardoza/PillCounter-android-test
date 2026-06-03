@file:Suppress("UNCHECKED_CAST")

package com.rite.pillcounting.feature.history.presentation.compose

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.dtos.TxnDetailInfo
import java.io.File

@Composable
fun TransactionDetailsList(
    transactionDetails: List<TxnDetailInfo>
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val itemModifier = Modifier
        .width(140.dp)
        .padding(4.dp)
        .then(if (isLandscape) Modifier.height(100.dp) else Modifier.height(110.dp))

    val circleSize = if (isLandscape) 45.dp else 40.dp

    val arrangement = if (isLandscape) Arrangement.spacedBy(8.dp) else Arrangement.spacedBy(8.dp)

    if (isLandscape) {
        LazyColumn(
            verticalArrangement = arrangement
        ) {
            items(
                items = transactionDetails,
                key = { detail -> "${detail.txnId}-${detail.imagePath ?: ""}-${detail.type}" }
            ) { detail ->
                TransactionDetailItem(detail, itemModifier, circleSize)
            }
        }
    } else {
        LazyRow(
            horizontalArrangement = arrangement
        ) {
            items(
                items = transactionDetails,
                key = { detail -> "${detail.txnId}-${detail.imagePath ?: ""}-${detail.type}" }
            ) { detail ->
                TransactionDetailItem(detail, itemModifier, circleSize)
            }
        }
    }
}

@Composable
private fun TransactionDetailItem(
    detail: TxnDetailInfo,
    modifier: Modifier,
    circleSize: Dp
) {
    Box(modifier = modifier) {
        val painter = detail.imagePath?.takeIf { it.isNotEmpty() }?.let {
            val file = File(it)
            rememberAsyncImagePainter(
                ImageRequest.Builder(LocalContext.current)
                    .data(file)
                    .size(420, 330) // 3x the ~140x110dp item box; Coil downsamples on decode
                    .placeholder(R.drawable.bottle)
                    .error(R.drawable.bottle)
                    .build()
            )
        } ?: painterResource(R.drawable.bottle)

        Image(
            painter = painter,
            contentDescription = null,
            contentScale = if (detail.imagePath.isNullOrEmpty()) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(circleSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .align(Alignment.Center)
        ) {
            Text(
                text = detail.pillCount.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
    }
}

