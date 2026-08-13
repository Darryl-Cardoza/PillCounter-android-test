package com.rite.pillcounting.feature.faceAuth.presentation

import Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.utils.compose.cardSelectionShadow
import com.rite.pillcounting.feature.countResume.presentation.compose.HeadlineBar
import com.rite.pillcounting.feature.faceAuth.presentation.viewmodel.FaceAuthViewModel
import com.rite.pillcounting.ui.theme.AppTheme
import com.rite.pillcounting.ui.theme.AppTheme.dimens

/**
 * Lists enrolled Quick Access face profiles as cards with an enable/disable toggle,
 * a multi-select delete mode (same [HeadlineBar] pattern as the count-history screens),
 * and an Add User tap-through to [Screen.FaceRegistration].
 *
 * @param navController Used to reach [Screen.FaceRegistration] (Add User).
 * @param viewModel Supplies the live profile list and toggle/delete actions.
 */
@Composable
fun FaceUsersListScreen(
    navController: NavController,
    viewModel: FaceAuthViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    var isDeleteMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val isAllSelected = selectedIds.size == profiles.size && profiles.isNotEmpty()

    val handleBack = {
        if (isDeleteMode) {
            isDeleteMode = false
            selectedIds = emptySet()
        } else {
            navController.popBackStack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground)
            .padding(vertical = 16.dp)
    ) {
        HeadlineBar(
            navController = navController,
            title = stringResource(R.string.face_users_title),
            searchQuery = "",
            showSearch = false,
            isMultiSelectMode = isDeleteMode,
            isAllSelected = isAllSelected,
            hasSelection = selectedIds.isNotEmpty(),
            showDelete = profiles.isNotEmpty(),
            showSearchIcon = false,
            onSearchClick = {},
            onSearchChange = {},
            onDeleteClick = { isDeleteMode = true },
            onCancelClick = { handleBack() },
            onConfirmDelete = {},
            onSelectAll = {
                selectedIds = if (isAllSelected) emptySet()
                else profiles.map { it.id }.toSet()
            },
            onBackClick = { handleBack() },
            deleteModeTitle = stringResource(R.string.face_users_delete_title)
        )

        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.face_users_empty),
                    color = AppTheme.extendedColors.textColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    FaceUserCard(
                        profile = profile,
                        isDeleteMode = isDeleteMode,
                        isSelected = profile.id in selectedIds,
                        onToggleSelect = {
                            selectedIds = if (profile.id in selectedIds)
                                selectedIds - profile.id
                            else
                                selectedIds + profile.id
                        },
                        onToggle = { enabled -> viewModel.setProfileEnabled(profile, enabled) }
                    )
                }
            }
        }

        if (isDeleteMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HollowButton(
                    text = stringResource(R.string.cancel).uppercase(),
                    onClick = { handleBack() },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(responsiveDp(120.dp))
                )
                ActionButtonPrimary(
                    text = stringResource(R.string.delete).uppercase(),
                    onClick = {
                        if (selectedIds.isNotEmpty()) showDeleteConfirmDialog = true
                    },
                    fixedWidth = true,
                    modifier = Modifier.width(dimens.dialogButtonWidth)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                ActionButtonPrimary(
                    text = stringResource(R.string.face_users_add_user).uppercase(),
                    onClick = { navController.navigate(Screen.FaceRegistration.route) },
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showDeleteConfirmDialog) {
        CommonDialog(
            title = stringResource(R.string.confirm_delete_title),
            message = stringResource(R.string.delete_selected_items_text),
            confirmText = stringResource(R.string.delete),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                profiles.filter { it.id in selectedIds }.forEach { viewModel.deleteProfile(it) }
                showDeleteConfirmDialog = false
                isDeleteMode = false
                selectedIds = emptySet()
            },
            onCancel = { showDeleteConfirmDialog = false }
        )
    }
}

@Composable
private fun FaceUserCard(
    profile: FaceProfileEntity,
    isDeleteMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardSelectionShadow(
                isActive = isDeleteMode && isSelected,
                selectionColor = MaterialTheme.colorScheme.secondary,
                cornerRadius = 8.dp
            )
            .background(AppTheme.extendedColors.secondaryBackground, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = isDeleteMode) { onToggleSelect() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.profile),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${profile.firstName} ${profile.lastName}",
                color = AppTheme.extendedColors.textColor,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge
            )
            profile.lastUsedAt?.let {
                Text(
                    text = stringResource(R.string.face_users_last_used, it.toFormattedDate()),
                    color = AppTheme.extendedColors.textColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        if (!isDeleteMode) {
            Switch(checked = profile.isEnabled, onCheckedChange = onToggle)
        }
    }
}
