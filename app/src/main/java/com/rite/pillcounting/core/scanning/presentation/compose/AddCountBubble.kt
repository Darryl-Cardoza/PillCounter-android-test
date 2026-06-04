package com.rite.pillcounting.core.scanning.presentation.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSp

@Composable
fun AddCountBubble(text: String) {
    Box(
        modifier = Modifier
            .padding(
                horizontal = 20.dp,
                vertical = 10.dp
            )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = responsiveSp(100.sp),
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            ),
            color =  MaterialTheme.colorScheme.secondary
        )
    }
}