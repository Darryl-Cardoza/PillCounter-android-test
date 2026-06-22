package com.rite.pillcounting.feature.settings.presentation

import Screen
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.ScheduleCode
import com.rite.pillcounting.feature.settings.presentation.viewmodel.MainActivityViewModel
import com.rite.pillcounting.core.utils.common.HistoryRetention
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.constants.LocalDimens
import com.rite.pillcounting.ui.theme.AppTheme
import com.rite.pillcounting.ui.theme.LocalExtendedColors

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: MainActivityViewModel = hiltViewModel()
) {
    val isAskToAddNotes by viewModel.isAskToAddNotes.collectAsState()

    val historyOptions = stringArrayResource(R.array.history_options).toList()
    val historyOptionDays = HistoryRetention.optionsDays
    val selectedDays by viewModel.selectedHistoryOption.collectAsState()
    val selectedOption = historyOptions[historyOptionDays.indexOf(selectedDays)]

    val extendedColors = LocalExtendedColors.current
    val isSoundEnabled by viewModel.isSoundOn.collectAsState()
    val isHapticEnable by viewModel.isHapticOn.collectAsState()
    val isRequireBackCountEnable by viewModel.isRequireBackCountEnable.collectAsState()
    var showCLearAllDataConfirmDialog by remember { mutableStateOf(false) }
    var showClearTrayColorListsDialog by remember { mutableStateOf(false) }
    val selectedSchedules by viewModel.selectedSchedules.collectAsState()
    val isSoundOverrideEnable by viewModel.isSoundOverride.collectAsState()
    val isHazardousDrug by viewModel.isHazardousDrug.collectAsState()
    val dimens = LocalDimens.current
    val context = LocalContext.current

    // HL7 disabled from the portal: these settings depend on HL7/PMS, so disable
    // them (dimmed + non-interactive) and surface a toast on tap.
    val hl7Enabled = viewModel.isHl7Enabled()
    val disabledAlpha = 0.4f
    val onHl7DisabledTap = { showToast(context, R.string.enable_hl7_from_portal_toast) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(extendedColors.secondaryBackground)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            BackButton(navController = navController)

            Text(
                text = stringResource(R.string.settings_title),
                fontSize = 16.sp,
                color = extendedColors.textColor
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingSwitch(
                labelRes = R.string.setting_ask_to_add_notes,
                checked = isAskToAddNotes,
                onCheckedChange = { newValue ->
                    viewModel.toggleAskToAddNotes(newValue)
                },
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (hl7Enabled) navController.navigate(Screen.RequireDoubleCount.route)
                        else onHl7DisabledTap()
                    }
                    .padding(vertical = 12.dp, horizontal = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.require_double_count),
                    fontSize = 16.sp,
                    color = if (hl7Enabled) extendedColors.textColor
                    else extendedColors.textColor.copy(alpha = disabledAlpha)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    ScheduleCode.entries.forEach { code ->
                        val codeColor = if (selectedSchedules.contains(code))
                            MaterialTheme.colorScheme.secondary
                        else
                            Color.Gray
                        Text(
                            text = code.name,
                            fontSize = 16.sp,
                            color = if (hl7Enabled) codeColor else codeColor.copy(alpha = disabledAlpha)
                        )
                    }
                }
            }

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            SettingSwitch(
                labelRes = R.string.require_back_count,
                checked = isRequireBackCountEnable,
                onCheckedChange = { newValue ->
                    viewModel.toggleRequireBackCountOnOff(newValue)
                },
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                enabled = hl7Enabled,
                onDisabledClick = onHl7DisabledTap
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate(Screen.SaveHistoryFor.route) }
                    .padding(vertical = 12.dp, horizontal = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_save_history_for),
                    fontSize = 16.sp,
                    color = extendedColors.textColor
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = selectedOption,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            SettingSwitch(
                labelRes = R.string.pill_counting_sound,
                checked = isSoundEnabled,
                onCheckedChange = { newValue ->
                    viewModel.toggleSoundOnOff(newValue)
                },
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            SettingSwitch(
                labelRes = R.string.haptic_feedback,
                checked = isHapticEnable,
                onCheckedChange = { newValue ->
                    viewModel.toggleHapticOnOff(newValue)
                },
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            SettingSwitch(
                labelRes = R.string.voice_feedback,
                checked = isSoundOverrideEnable,
                onCheckedChange = { newValue ->
                    viewModel.toggleSoundOverride(newValue)
                },
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            SettingSwitch(
                labelRes = R.string.setting_hazardous_drug,
                checked = isHazardousDrug,
                onCheckedChange = { newValue ->
                    viewModel.toggleHazardousDrug(newValue)
                },
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                enabled = hl7Enabled,
                onDisabledClick = onHl7DisabledTap
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            Text(
                text = stringResource(R.string.clear_tray_color_lists),
                fontSize = 16.sp,
                color = if (hl7Enabled) extendedColors.textColor
                else extendedColors.textColor.copy(alpha = disabledAlpha),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (hl7Enabled) showClearTrayColorListsDialog = true
                        else onHl7DisabledTap()
                    }
                    .padding(vertical = dimens.settingRowVerticalPadding, horizontal = 16.dp)
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            Text(
                text = stringResource(R.string.clear_all_local_data),
                fontSize = 16.sp,
                color = extendedColors.textColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showCLearAllDataConfirmDialog = true
                    }
                    .padding(vertical = dimens.settingRowVerticalPadding, horizontal = 16.dp)
            )

        }
    }

    if (showCLearAllDataConfirmDialog) {
        CommonDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_clear_all_data),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                showCLearAllDataConfirmDialog = true
                viewModel.deleteAllTransaction()
                showCLearAllDataConfirmDialog = false
            },
            onCancel = { showCLearAllDataConfirmDialog = false }
        )
    }

    if (showClearTrayColorListsDialog) {
        CommonDialog(
            message = stringResource(R.string.clear_tray_color_lists_confirm),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                viewModel.clearTrayColorLists()
                showClearTrayColorListsDialog = false
            },
            onCancel = { showClearTrayColorListsDialog = false }
        )
    }
}

@Composable
fun SettingSwitch(
    @StringRes labelRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    checkedTrackColor: Color = MaterialTheme.colorScheme.secondary,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null
) {
    val extendedColors = LocalExtendedColors.current
    val dimens = LocalDimens.current

    val textColor =
        if (enabled) extendedColors.textColor else extendedColors.textColor.copy(alpha = 0.4f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // When disabled, the switch is non-interactive; let the whole row be
            // tapped so the caller can surface a toast.
            .then(
                if (!enabled && onDisabledClick != null)
                    Modifier.clickable { onDisabledClick() }
                else Modifier
            )
            .padding(vertical = dimens.settingRowVerticalPadding, horizontal = 16.dp)
    ) {
        Text(
            text = stringResource(labelRes),
            fontSize = 16.sp,
            color = textColor,
            modifier = Modifier.weight(1f)
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = checkedTrackColor,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
                checkedBorderColor = Color.Transparent
            ),
            thumbContent = null
        )
    }
}