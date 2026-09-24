package com.dispensesure.retail.feature.settings.presentation

import androidx.annotation.StringRes
import Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.ScheduleCode
import com.dispensesure.retail.feature.settings.presentation.viewmodel.MainActivityViewModel
import com.dispensesure.retail.core.utils.common.HistoryRetention
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.BackButton
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.CommonDialog
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.showToast
import com.dispensesure.retail.core.utils.constants.LocalDimens
import com.dispensesure.retail.navigation.Screen
import com.dispensesure.retail.ui.theme.AppTheme
import com.dispensesure.retail.ui.theme.LocalExtendedColors

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
    val isUseStaticPmsConnection by viewModel.isUseStaticPmsConnection.collectAsState()
    val faceLockTimeoutMinutes by viewModel.faceLockTimeoutMinutes.collectAsState()
    var showAutoLockDialog by remember { mutableStateOf(false) }
    val autoLockMinuteOptions = listOf(1, 2, 5, 10)
    val dimens = LocalDimens.current
    val context = LocalContext.current
    var showConnectionInfo by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    // History option picked in the dialog, waiting on the delete-old-data confirm.
    var pendingHistoryDays by remember { mutableStateOf<Int?>(null) }
    var showDoubleCountDialog by remember { mutableStateOf(false) }

    // Index of the open group card, null when all are closed. General starts open;
    // tapping the open card closes it, tapping another swaps to it.
    var openCard by rememberSaveable { mutableStateOf<Int?>(1) }
    val onCardClick = { index: Int -> openCard = if (openCard == index) null else index }

    // HL7 disabled from the portal: these settings depend on HL7/PMS, so disable
    // them (dimmed + non-interactive) and surface a toast on tap.
    val hl7Enabled = viewModel.isHl7Enabled()
    val onHl7DisabledTap = { showToast(context, R.string.enable_hl7_from_portal_toast) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(extendedColors.secondaryBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .displayCutoutPadding()
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
                verticalArrangement = Arrangement.spacedBy(dimens.settingsCardSpacing),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = dimens.pagePadding, end = dimens.pagePadding, bottom = dimens.pagePadding)
                    .verticalScroll(rememberScrollState())
            ) {

                SettingsGroupCard(
                    titleRes = R.string.setting_group_controlled_drug,
                    expanded = openCard == 1,
                    onHeaderClick = { onCardClick(1) },
                    rows = listOf<@Composable () -> Unit>(
                        {
                            SettingsRow(
                                labelRes = R.string.require_double_count,
                                onClick = { showDoubleCountDialog = true },
                                enabled = hl7Enabled,
                                onDisabledClick = onHl7DisabledTap,
                                value = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ScheduleCode.entries.forEach { code ->
                                            val codeColor = if (selectedSchedules.contains(code))
                                                MaterialTheme.colorScheme.secondary
                                            else
                                                Color.Gray
                                            Text(
                                                text = code.name,
                                                fontSize = 14.sp,
                                                color = if (hl7Enabled) codeColor
                                                else codeColor.copy(alpha = SETTINGS_DISABLED_ALPHA)
                                            )
                                        }
                                    }
                                }
                            )
                        },
                        {
                            SettingsRow(
                                labelRes = R.string.require_back_count,
                                checked = isRequireBackCountEnable,
                                onCheckedChange = { viewModel.toggleRequireBackCountOnOff(it) },
                                enabled = hl7Enabled,
                                onDisabledClick = onHl7DisabledTap
                            )
                        }
                    )
                )

                SettingsGroupCard(
                    titleRes = R.string.setting_group_face_detection,
                    expanded = openCard == 2,
                    onHeaderClick = { onCardClick(2) },
                    rows = listOf<@Composable () -> Unit>(
                        {
                            SettingsRow(
                                labelRes = R.string.setting_face_auto_lock_after,
                                onClick = { showAutoLockDialog = true },
                                value = {
                                    SettingsValueText(
                                        stringResource(R.string.setting_face_auto_lock_minutes, faceLockTimeoutMinutes)
                                    )
                                }
                            )
                        },
                        {
                            SettingsRow(
                                labelRes = R.string.setting_face_recognition_users,
                                onClick = { navController.navigate(Screen.FaceRecognitionUsers.route) }
                            )
                        }
                    )
                )

                SettingsGroupCard(
                    titleRes = R.string.setting_group_voice_haptic,
                    expanded = openCard == 3,
                    onHeaderClick = { onCardClick(3) },
                    rows = listOf<@Composable () -> Unit>(
                        {
                            SettingsRow(
                                labelRes = R.string.voice_feedback,
                                checked = isSoundOverrideEnable,
                                onCheckedChange = { viewModel.toggleSoundOverride(it) }
                            )
                        },
                        {
                            SettingsRow(
                                labelRes = R.string.pill_counting_sound,
                                checked = isSoundEnabled,
                                onCheckedChange = { viewModel.toggleSoundOnOff(it) }
                            )
                        },
                        {
                            SettingsRow(
                                labelRes = R.string.haptic_feedback,
                                checked = isHapticEnable,
                                onCheckedChange = { viewModel.toggleHapticOnOff(it) }
                            )
                        }
                    )
                )

                SettingsGroupCard(
                    titleRes = R.string.setting_group_hazardous,
                    expanded = openCard == 4,
                    onHeaderClick = { onCardClick(4) },
                    rows = listOf<@Composable () -> Unit>(
                        {
                            SettingsRow(
                                labelRes = R.string.setting_hazardous_drug,
                                checked = isHazardousDrug,
                                onCheckedChange = { viewModel.toggleHazardousDrug(it) },
                                enabled = hl7Enabled,
                                onDisabledClick = onHl7DisabledTap
                            )
                        },
                        {
                            SettingsRow(
                                labelRes = R.string.clear_tray_color_lists,
                                onClick = { showClearTrayColorListsDialog = true },
                                enabled = hl7Enabled,
                                onDisabledClick = onHl7DisabledTap
                            )
                        }
                    )
                )

                SettingsGroupCard(
                    titleRes = R.string.setting_group_general,
                    expanded = openCard == 0,
                    onHeaderClick = { onCardClick(0) },
                    rows = buildList<@Composable () -> Unit> {
                        add {
                            SettingsRow(
                                labelRes = R.string.setting_ask_to_add_notes,
                                checked = isAskToAddNotes,
                                onCheckedChange = { viewModel.toggleAskToAddNotes(it) }
                            )
                        }
                        add {
                            SettingsRow(
                                labelRes = R.string.setting_save_history_for,
                                onClick = { showHistoryDialog = true },
                                value = { SettingsValueText(selectedOption) }
                            )
                        }
                        add {
                            SettingsRow(
                                labelRes = R.string.clear_all_local_data,
                                onClick = { showCLearAllDataConfirmDialog = true }
                            )
                        }
                        if (isUseStaticPmsConnection) {
                            add {
                                SettingsRow(
                                    labelRes = R.string.setting_pms_connection,
                                    onClick = { showConnectionInfo = true }
                                )
                            }
                        }
                    }
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

        if (showConnectionInfo) {
            ConnectionInfoScreen(onBackClick = { showConnectionInfo = false })
        }

        if (showAutoLockDialog) {
            CommonSingleSelectDialog(
                title = stringResource(R.string.setting_face_auto_lock_after),
                options = autoLockMinuteOptions.map { stringResource(R.string.setting_face_auto_lock_minutes, it) },
                selectedIndex = autoLockMinuteOptions.indexOf(faceLockTimeoutMinutes).takeIf { it >= 0 },
                confirmText = stringResource(R.string.save),
                onCancel = { showAutoLockDialog = false },
                onOk = { index ->
                    viewModel.updateFaceLockTimeoutMinutes(autoLockMinuteOptions[index])
                    showAutoLockDialog = false
                }
            )
        }

        if (showHistoryDialog) {
            CommonSingleSelectDialog(
                title = stringResource(R.string.save_history_for_title),
                options = historyOptions,
                selectedIndex = historyOptionDays.indexOf(selectedDays).takeIf { it >= 0 },
                confirmText = stringResource(R.string.save),
                onCancel = { showHistoryDialog = false },
                onOk = { index ->
                    showHistoryDialog = false
                    val days = historyOptionDays[index]
                    // Same option: nothing to confirm or purge.
                    if (days != selectedDays) pendingHistoryDays = days
                }
            )
        }

        pendingHistoryDays?.let { days ->
            CommonDialog(
                message = stringResource(R.string.save_history_note),
                title = "${stringResource(R.string.save_history_confirmation)} ${historyOptions[historyOptionDays.indexOf(days)]}?",
                confirmText = stringResource(R.string.yes),
                cancelText = stringResource(R.string.no),
                onConfirm = {
                    viewModel.updateHistoryOption(days)
                    pendingHistoryDays = null
                },
                onCancel = { pendingHistoryDays = null }
            )
        }

        if (showDoubleCountDialog) {
            val schedules = ScheduleCode.entries
            CommonMultiSelectDialog(
                title = stringResource(R.string.require_double_count_title),
                options = schedules.map { it.name },
                selectedIndices = schedules.indices.filter { schedules[it] in selectedSchedules }.toSet(),
                confirmText = stringResource(R.string.save),
                onCancel = { showDoubleCountDialog = false },
                onConfirm = { indices ->
                    viewModel.updateSchedules(indices.map { schedules[it] }.toSet())
                    showDoubleCountDialog = false
                }
            )
        }
    }
}
