package com.rite.pillcounting.feature.settings.presentation

import Screen
import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.settings.domain.model.enums.ScheduleCode
import com.rite.pillcounting.core.settings.presentation.viewmodel.MainActivityViewModel
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.ui.theme.LocalExtendedColors

@SuppressLint("UnrememberedGetBackStackEntry")
@Composable
fun SaveCsDoubleCountScreen(
    navController: NavController,
) {
    val settingsEntry = remember(navController) { navController.getBackStackEntry(Screen.Settings.route) }
    val viewModel: MainActivityViewModel = hiltViewModel(settingsEntry)
    val selectedSchedules by viewModel.selectedSchedules.collectAsState()
    val schedules = ScheduleCode.entries
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
                text = stringResource(R.string.require_double_count_title),
                fontSize = 16.sp,
                color = extendedColors.textColor
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            schedules.forEach { code ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleSchedule(code) }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = selectedSchedules.contains(code),
                        onCheckedChange = { viewModel.toggleSchedule(code) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = extendedColors.textColor,
                            checkmarkColor = Color.White
                        )
                    )
                    Text(
                        text = code.name,
                        fontSize = 16.sp,
                        color = extendedColors.textColor,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                HorizontalDivider(color = colorResource(R.color.border_gray).copy(alpha = 0.3f))
            }
        }
    }
}
