package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.core.CalendarDay
import java.time.LocalDate

@Composable
fun DayCell(
    day: CalendarDay,
    isStart: Boolean,
    isEnd: Boolean,
    isInRange: Boolean,
    onClick: () -> Unit
) {
    val today = LocalDate.now()
    val isFuture = day.date.isAfter(today)
    val isToday = day.date == today
    val primary = MaterialTheme.colorScheme.primary
    val config = LocalConfiguration.current
    val isTablet = minOf(config.screenWidthDp, config.screenHeightDp) >= 600
    val rangeCapPadding = if (isTablet) 40.dp else 20.dp

    val sliderShape = when {
        isStart && isEnd -> RoundedCornerShape(50)
        isStart -> RoundedCornerShape(
            topStart = 20.dp,
            bottomStart = 20.dp,
            topEnd = 0.dp,
            bottomEnd = 0.dp
        )
        isEnd -> RoundedCornerShape(
            topStart = 0.dp,
            bottomStart = 0.dp,
            topEnd = 20.dp,
            bottomEnd = 20.dp
        )
        isInRange -> RoundedCornerShape(0.dp)
        else -> null
    }

    Box(
        modifier = Modifier
            .height(48.dp)
            .fillMaxWidth()
            .then(
                if (!isFuture) Modifier.clickable(
                    onClick = onClick,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        val isSingleDay = isStart && isEnd

        // Slider range background — fills full cell height so rows form a continuous strip
        if ((isInRange || isEnd) && !isSingleDay) {
            val horizontalPaddingStart = if (isStart) rangeCapPadding else 0.dp
            val horizontalPaddingEnd = if (isEnd) rangeCapPadding else 0.dp

            Box(
                modifier = Modifier
                    .height(40.dp)
                    .fillMaxWidth()
                    .padding(
                        start = horizontalPaddingStart,
                        end = horizontalPaddingEnd
                    )
                    .background(
                        color = primary.copy(alpha = 0.25f),
                        shape = sliderShape ?: RoundedCornerShape(0.dp)
                    )
            )
        }

        // Selected (start / end) circle
        if (isStart || isEnd) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(primary, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = day.date.dayOfMonth.toString(),
                    color = Color.White
                )
            }
        }

        // In-range middle text
        else if (isInRange) {
            Text(
                text = day.date.dayOfMonth.toString(),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Future dates — grayed out
        else if (isFuture) {
            Text(
                text = day.date.dayOfMonth.toString(),
                color = Color.Gray.copy(alpha = 0.4f)
            )
        }

        // Today — primary color + bold
        else if (isToday) {
            Text(
                text = day.date.dayOfMonth.toString(),
                color = primary,
                fontWeight = FontWeight.Bold
            )
        }

        // Normal past date
        else {
            Text(
                text = day.date.dayOfMonth.toString(),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
