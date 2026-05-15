package com.rite.pillcounting.feature.settings.presentation

import Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.settings.presentation.viewmodel.MainActivityViewModel
import com.rite.pillcounting.core.utils.common.HistoryRetention
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.ui.theme.AppTheme
import com.rite.pillcounting.ui.theme.LocalExtendedColors

@Composable
fun SaveHistoryForScreen(
    navController: NavController,
) {
    val settingsEntry = remember(navController) { navController.getBackStackEntry(Screen.Settings.route) }
    val viewModel: MainActivityViewModel = hiltViewModel(settingsEntry)
    val historyOptions = stringArrayResource(R.array.history_options).toList()
    val historyOptionDays = HistoryRetention.optionsDays
    val selectedDays by viewModel.selectedHistoryOption.collectAsState()
    val selectedOption = historyOptions[historyOptionDays.indexOf(selectedDays)]

    var showConfirmationDialog by remember { mutableStateOf(false) }
    var tempSelectedOption by remember { mutableStateOf("") }
    val dimens = AppTheme.dimens
    val extendedColors = LocalExtendedColors.current

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
                text = stringResource(R.string.save_history_for_title),
                fontSize = 16.sp,
                color = extendedColors.textColor
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            historyOptions.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (option != selectedOption) {
                                tempSelectedOption = option
                                showConfirmationDialog = true
                            }
                        }
                        .padding(vertical =dimens.small, horizontal = 8.dp)
                ) {
                    RadioButton(
                        selected = selectedOption == option,
                        onClick = {
                            if (option != selectedOption) {
                                tempSelectedOption = option
                                showConfirmationDialog = true
                            }
                        },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Text(
                        text = option,
                        fontSize = 16.sp,
                        color = extendedColors.textColor,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
//                HorizontalDivider(color = colorResource(R.color.border_gray).copy(alpha = 0.3f))
            }
        }
    }

    if (showConfirmationDialog) {
        val days = historyOptionDays[historyOptions.indexOf(tempSelectedOption)]
        CommonDialog(
            message = stringResource(R.string.save_history_note),
            title = "${stringResource(R.string.save_history_confirmation)} $tempSelectedOption?",
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                viewModel.updateHistoryOption(days)
                showConfirmationDialog = false
                navController.popBackStack()
            },
            onCancel = {
                showConfirmationDialog = false
            }
        )
    }
}
