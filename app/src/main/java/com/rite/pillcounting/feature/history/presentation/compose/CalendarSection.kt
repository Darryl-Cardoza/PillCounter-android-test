package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.ui.theme.AppTheme
import com.kizitonwose.calendar.compose.CalendarState
import com.kizitonwose.calendar.compose.VerticalCalendar
import com.rite.pillcounting.feature.history.presentation.viewmodel.HistoryViewModel
import java.time.LocalDate
import java.util.Locale
import androidx.compose.runtime.collectAsState
import com.kizitonwose.calendar.core.DayPosition
import com.rite.pillcounting.feature.countResume.presentation.compose.HeadlineBar

/**
 * Calendar section of the History screen.
 * Purely UI; all business logic (selected date, calendar state) is handled by the ViewModel.
 *
 * @param appTheme Theme wrapper for extended colors.
 * @param calendarState State object for the [VerticalCalendar].
 * @param selectedDate Currently selected date from ViewModel.
 * @param onDateSelected Callback to notify ViewModel when a date is selected.
 * @param onBackClick Callback when the back button is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSection(
    calendarState: CalendarState,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onBackClick: () -> Unit,
    navController: NavController,
    viewModel: HistoryViewModel,
    showSearch: Boolean,
    onSearchToggle: (Boolean) -> Unit
) {

    // ADD THESE HERE
    val searchQuery by viewModel.searchQuery.collectAsState()
    val startDate by viewModel.startDate.collectAsState()
    val endDate by viewModel.endDate.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        // Top bar: back button + screen title
        HeadlineBar(
            navController = navController,
            title = stringResource(R.string.history_title),
            searchQuery = searchQuery,
            showSearch = showSearch,
            isMultiSelectMode = false,
            isAllSelected = false,
            hasSelection = false,
            showDelete = false,
            onSearchClick = {
                val newState = !showSearch
                onSearchToggle(newState)

                if (!newState) {
                    viewModel.setSearchQuery("")
                }
            },
            onSearchChange = { viewModel.setSearchQuery(it) },
            onDeleteClick = { /* not used in history */ },
            onCancelClick = { /* not used */ },
            onConfirmDelete = { /* not used */ },
            onSelectAll = { /* not used */ }
        )
        // Vertical calendar → only handles UI rendering
        VerticalCalendar(
            state = calendarState,
            modifier = Modifier.fillMaxSize().padding(8.dp),
            monthBody = { _, content ->
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.padding(bottom = 16.dp)
                ) { content() }
            },
            monthHeader = { calendarMonth ->
                val month = calendarMonth.yearMonth
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "${month.month.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }} ${month.year}",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            },
            dayContent = { day ->

                if (day.position == DayPosition.MonthDate) {

                    val date = day.date

                    val isStart = startDate == date
                    val isEnd = endDate == date
                    val isInRange = date in startDate..endDate

                    DayCell(
                        day = day,
                        isStart = isStart,
                        isEnd = isEnd,
                        isInRange = isInRange,
                        onClick = {

                            val currentStart = startDate
                            val currentEnd = endDate

                            when {

                                // If currently single day selected
                                currentStart == currentEnd -> {
                                    if (date >= currentStart) {
                                        viewModel.setDateRange(currentStart, date)
                                    } else {
                                        viewModel.setDateRange(date, currentStart)
                                    }
                                }

                                // If range already selected → start new selection
                                else -> {
                                    viewModel.setDateRange(date, date)
                                }
                            }
                        }
                    )

                } else {
                    Spacer(
                        modifier = Modifier
                            .height(48.dp)
                            .fillMaxWidth()
                    )
                }
            }
        )
    }
}
