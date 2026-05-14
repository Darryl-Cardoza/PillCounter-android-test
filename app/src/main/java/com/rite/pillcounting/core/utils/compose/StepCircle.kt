package com.rite.pillcounting.core.utils.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.models.StepStatus
import com.rite.pillcounting.core.models.icon
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun StepCircle(
    step: StepState,
    state: StepStatus
) {

    val backgroundColor = when (state) {
        StepStatus.DONE -> AppTheme.extendedColors.secondaryBackground
        StepStatus.ACTIVE -> AppTheme.extendedColors.secondaryBackground
        StepStatus.PENDING -> AppTheme.extendedColors.secondaryBackground.copy(alpha = 0.4f)
    }

    Box(
        modifier = Modifier
            .size(responsiveDp(35.dp))
            .background(backgroundColor, CircleShape)
            .then(
                if (state == StepStatus.ACTIVE)
                    Modifier.border(1.dp, MaterialTheme.colorScheme.secondary, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = step.icon()),
            contentDescription = step.name,
            tint = AppTheme.extendedColors.textColor,
            modifier = Modifier.size(responsiveDp(16.dp))
        )
    }
}