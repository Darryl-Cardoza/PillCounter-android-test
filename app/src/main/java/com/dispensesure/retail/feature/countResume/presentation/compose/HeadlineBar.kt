package com.dispensesure.retail.feature.countResume.presentation.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.BackButton
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.feature.history.presentation.compose.ActionIcon
import com.dispensesure.retail.ui.theme.AppTheme

@Composable
fun HeadlineBar(
    navController: NavController,
    title: String,
    searchQuery: String,
    showSearch: Boolean,
    isMultiSelectMode: Boolean,
    isAllSelected: Boolean,
    hasSelection: Boolean,
    showDelete: Boolean,
    onSearchClick: () -> Unit,
    onSearchChange: (String) -> Unit,
    onDeleteClick: () -> Unit,
    onCancelClick: () -> Unit,
    onConfirmDelete: () -> Unit,
    onSelectAll: () -> Unit,
    showSearchIcon: Boolean = true,
    showPdfIcon: Boolean = false,
    onPdfClick: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    deleteModeTitle: String = stringResource(R.string.delete_counts),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 12.dp, bottom = 8.dp, start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (showSearch) {
            // Search mode
            val searchFocusRequester = remember { FocusRequester() }

            // Opening search puts the caret straight in the field, so typing
            // works without a second tap. Wait one frame first — requesting
            // focus before the field is placed throws "not initialized".
            LaunchedEffect(Unit) {
                withFrameNanos { }
                searchFocusRequester.requestFocus()
            }

            Row(
                modifier = Modifier
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {

                TextField(
                    value = searchQuery,
                    onValueChange = { onSearchChange(it) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocusRequester),
                    placeholder = { Text(stringResource(R.string.searchWithDots)) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = AppTheme.extendedColors.textColor,
                        unfocusedTextColor = AppTheme.extendedColors.textColor,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = Color.Gray
                    )
                )
            }

            Spacer(Modifier.width(8.dp))

            ActionIcon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.close_app),
                onClick = {
                    onSearchClick()
                }
            )

        } else {
            //  Normal mode
            // when delete mode is on
            if (!isMultiSelectMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    BackButton(navController,onClick = onBackClick)

                    Spacer(Modifier.width(4.dp))

                    Text(
                        text = title.uppercase(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.extendedColors.textColor,
                        modifier = Modifier.weight(1f)
                    )

                    // SEARCH ICON (optional now)
                    if (showSearchIcon) {
                        Icon(
                            painter = painterResource(id = R.drawable.search),
                            contentDescription = stringResource(R.string.cd_search),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(responsiveDp(20.dp))
                                .clickable { onSearchClick() }
                        )
                    }

                    // PDF ICON (new optional support)
                    if (showPdfIcon) {

                        Spacer(Modifier.width(16.dp))

                        Icon(
                            painter = painterResource(id = R.drawable.pdf),
                            contentDescription = "Export PDF",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(responsiveDp(25.dp))
                                .clickable { onPdfClick?.invoke() }
                        )
                    }

                    // DELETE ICON (unchanged behavior)
                    if (showDelete) {

                        Spacer(Modifier.width(16.dp))

                        Icon(
                            painter = painterResource(id = R.drawable.delete),
                            contentDescription = stringResource(R.string.cd_select_items_to_delete),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(responsiveDp(25.dp))
                                .clickable { onDeleteClick() }
                        )
                    }
                }
            } else {
                // Delete mode header: back-as-cancel + "DELETE COUNTS" + SELECT ALL
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BackButton(navController, onClick = onCancelClick)

                    Spacer(Modifier.width(4.dp))

                    Text(
                        text = deleteModeTitle.uppercase(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.extendedColors.textColor,
                        modifier = Modifier.weight(1f)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onSelectAll() }
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isAllSelected) R.drawable.deselect_all_image
                                else R.drawable.select_all_image
                            ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (isAllSelected) R.string.deselect_all else R.string.select_all
                            ),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
