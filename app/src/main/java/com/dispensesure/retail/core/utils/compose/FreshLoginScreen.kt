package com.dispensesure.retail.core.utils.compose

import Screen
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
import androidx.navigation.NavController
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.dispensesure.retail.ui.theme.AppTheme

/**
 * A composable screen that informs the user about suspicious/unusual activity
 * and provides a direct navigation path to the login screen.
 *
 * ## Purpose
 * - To alert the user when suspicious account activity is detected.
 * - To enforce a re-login for security reasons.
 *
 * ## UI Layout
 * - Circular icon with a suspicious activity illustration.
 * - Warning/informational text.
 * - A primary action button leading the user to the login screen.
 *
 * ## Usage
 * This screen can be shown when:
 * - A session is invalidated due to unusual login attempts.
 * - Backend flags suspicious account activity.
 *
 * Example:
 * ```
 * FreshLoginScreen(navController = navController)
 * ```
 *
 * @param navController The [NavController] used for navigating to the login screen.
 * @param modifier Optional [Modifier] to adjust the screen layout or styling.
 */
@Composable
fun FreshLoginScreen(
    navController: NavController,
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
            // Suspicious activity icon inside a circular container
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(AppTheme.extendedColors.primaryBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.unusual_activity),
                    contentDescription = stringResource(R.string.suspecious_activity_text),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Suspicious activity message
            Text(
                text = stringResource(R.string.suspecious_activity_text),
                textAlign = TextAlign.Center,
                color = AppTheme.extendedColors.textColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Login Button
            ActionButtonPrimary(
                text = stringResource(R.string.login_button_text), // externalized "LOGIN"
                onClick = {
                    navController.navigate(Screen.Login.route)
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
