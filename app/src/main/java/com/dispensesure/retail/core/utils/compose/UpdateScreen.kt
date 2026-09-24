package com.dispensesure.retail.core.utils.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.dispensesure.retail.ui.theme.AppTheme

/**
 * A composable screen that prompts the user to update the application.
 *
 * ## Purpose
 * - Ensures users are notified when a newer app version is available.
 * - Provides a visually clear call-to-action with an icon, description, and update button.
 *
 * ## UI Layout
 * - Circular icon background with a centered update icon.
 * - Informative message text (localized via [stringResource]).
 * - Primary "UPDATE" action button that can be customized with a provided action.
 * @param onUpdateClick Callback triggered when the user taps the "UPDATE" button.
 * @param modifier [Modifier] for customizing layout or styling of the entire screen.
 */
@Composable
fun UpdateScreen(
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .displayCutoutPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            // App update icon inside a circular background
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(AppTheme.extendedColors.primaryBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.force_update),
                    contentDescription = stringResource(R.string.update_version_text),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Update instruction text
            Text(
                text = stringResource(R.string.update_version_text),
                textAlign = TextAlign.Center,
                color = AppTheme.extendedColors.textColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(28.dp))

            // Primary action button
            ActionButtonPrimary(
                text = stringResource(id = R.string.update_button_text), // externalize "UPDATE"
                onClick = onUpdateClick,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
