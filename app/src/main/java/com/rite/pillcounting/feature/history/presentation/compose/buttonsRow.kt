package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.FilledButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton

@Composable
fun ButtonsRow(
    onDelete: () -> Unit,
    onOk: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        HollowButton(
            text = stringResource(R.string.delete).uppercase(),
            onClick = onDelete,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 20.dp)
        )
        FilledButton(
            text = stringResource(R.string.ok).uppercase(),
            onClick = onOk,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 20.dp)
        )
    }
}
