package com.rite.pillcounting.feature.faceAuth.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.TagFaces
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * "Setup Quick Access" intro screen shown before face enrollment.
 * Not wired into navigation yet — hook up [onSkip] and [onGetStarted] when integrating.
 */
@Composable
fun FaceIntroScreen(
    onSkip: () -> Unit = {},
    onGetStarted: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .border(width = 2.dp, color = MaterialTheme.colorScheme.primary, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.CropFree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(88.dp)
                )
                Icon(
                    imageVector = Icons.Outlined.TagFaces,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.face_registration_title),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.face_intro_body),
                color = AppTheme.extendedColors.textColor,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HollowButton(
                text = stringResource(R.string.face_intro_skip).uppercase(),
                onClick = onSkip,
                color = MaterialTheme.colorScheme.primary,
                fixedWidth = false
            )
            ActionButtonPrimary(
                text = stringResource(R.string.face_intro_get_started).uppercase(),
                onClick = onGetStarted,
                color = MaterialTheme.colorScheme.primary,
                fixedWidth = false
            )
        }
    }
}
