package com.rite.pillcounting.feature.unsyncedTransaction.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.feature.unsyncedTransaction.presentation.viewmodel.UnsyncedTransactionViewModel
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun UnsyncedTransactionScreen(
    navController: NavController,
    viewModel: UnsyncedTransactionViewModel = hiltViewModel()
) {
    val uiState by viewModel.unsyncedTransactionUiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(navController)

                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.unsynced_transaction),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppTheme.extendedColors.textColor,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(48.dp))
            }

            UnsyncedTransactionList(
                dispenseList = uiState.dispenseList,
                batchList = uiState.batchList,
            )
        }
    }

}
