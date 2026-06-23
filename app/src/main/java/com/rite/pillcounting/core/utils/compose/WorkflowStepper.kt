package com.rite.pillcounting.core.utils.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.models.StepStatus

@Composable
fun WorkflowStepper(
    steps: List<StepState>,
    currentStep: StepState,
    modifier: Modifier = Modifier
) {

    val currentIndex = steps.indexOf(currentStep).coerceAtLeast(0)

    Row(
        // fillMaxWidth first so a width constraint supplied by the caller via
        // [modifier] (e.g. wrapContentWidth when embedded inline in a row) wins.
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {

        steps.forEachIndexed { index, step ->

            val state = when {
                index < currentIndex -> StepStatus.DONE
                index == currentIndex -> StepStatus.ACTIVE
                else -> StepStatus.PENDING
            }

            StepCircle(
                step = step,
                state = state
            )

            if (index < steps.lastIndex) {

                Spacer(modifier = Modifier.width(6.dp))

                StepArrow()

                Spacer(modifier = Modifier.width(6.dp))
            }
        }
    }
}