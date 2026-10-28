package com.dispensesure.retail.core.utils.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dispensesure.retail.R

// A drug the server sent without a name is saved with none; show it as "Unknown Drug".
fun Resources.drugDisplayName(drugName: String?): String =
    drugName?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_drug)

@Composable
fun drugDisplayName(drugName: String?): String =
    drugName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unknown_drug)
