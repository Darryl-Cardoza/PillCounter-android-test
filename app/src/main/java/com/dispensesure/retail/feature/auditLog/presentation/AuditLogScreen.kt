package com.dispensesure.retail.feature.auditLog.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.BackButton
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.showToast
import com.dispensesure.retail.core.utils.constants.LocalDimens
import com.dispensesure.retail.ui.theme.LocalExtendedColors

/** Debug-only list of decrypted audit records, newest first, with an Export action. */
@Composable
fun AuditLogScreen(
    navController: NavController,
    viewModel: AuditLogViewModel = hiltViewModel()
) {
    val formattedEntries by viewModel.formattedEntriesNewestFirst.collectAsState()
    val extendedColors = LocalExtendedColors.current
    val dimens = LocalDimens.current
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.exportResults.collect { exportedPath ->
            if (exportedPath != null) showToast(context.getString(R.string.audit_log_exported, exportedPath))
            else showToast(context, R.string.audit_log_export_failed)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(extendedColors.secondaryBackground)
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
                text = stringResource(R.string.audit_log_title),
                color = extendedColors.textColor,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = viewModel::export) {
                Text(stringResource(R.string.audit_log_export))
            }
        }

        if (formattedEntries.isEmpty()) {
            Text(
                text = stringResource(R.string.audit_log_empty),
                color = extendedColors.textColor,
                modifier = Modifier.padding(dimens.pagePadding)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(dimens.pagePadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(formattedEntries) { formattedEntry ->
                    Text(
                        text = formattedEntry,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = extendedColors.textColor
                    )
                }
            }
        }
    }
}
