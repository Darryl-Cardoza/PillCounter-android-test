package com.rite.pillcounting.feature.faceAuth.presentation

import Screen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.feature.faceAuth.presentation.viewmodel.FaceAuthViewModel

/**
 * Lists enrolled Quick Access face profiles, with enable/disable, delete, add, and
 * a tap-through to [FaceVerifyScreen] for a manual verify test.
 *
 * @param navController Used to reach [Screen.FaceRegistration] (Add User) and [Screen.FaceVerify].
 * @param viewModel Supplies the live profile list and toggle/delete actions.
 */
@Composable
fun FaceUsersListScreen(
    navController: NavController,
    viewModel: FaceAuthViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(navController = navController)
            Text(text = stringResource(R.string.face_users_title), style = MaterialTheme.typography.titleMedium)
        }

        if (profiles.isEmpty()) {
            Text(text = stringResource(R.string.face_users_empty), modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(profiles, key = { it.id }) { profile ->
                    FaceUserRow(
                        profile = profile,
                        onToggle = { enabled -> viewModel.setProfileEnabled(profile, enabled) },
                        onDelete = { viewModel.deleteProfile(profile) },
                        onTestVerify = { navController.navigate(Screen.FaceVerify.route) }
                    )
                    HorizontalDivider()
                }
            }
        }

        Button(
            onClick = { navController.navigate(Screen.FaceRegistration.route) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text(stringResource(R.string.face_users_add_user))
        }
    }
}

@Composable
private fun FaceUserRow(
    profile: FaceProfileEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTestVerify: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "${profile.firstName} ${profile.lastName}", style = MaterialTheme.typography.bodyLarge)
            profile.lastUsedAt?.let {
                Text(
                    text = stringResource(R.string.face_users_last_used, it.toString()),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Button(onClick = onTestVerify) { Text(stringResource(R.string.face_users_test)) }
        Switch(checked = profile.isEnabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
        }
    }
}
