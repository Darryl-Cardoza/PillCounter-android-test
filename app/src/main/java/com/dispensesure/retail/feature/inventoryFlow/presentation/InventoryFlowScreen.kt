package com.dispensesure.retail.feature.inventoryFlow.presentation

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavController
import com.dispensesure.retail.feature.inventoryFlow.presentation.shell.InventoryPhoneLandscapeShell
import com.dispensesure.retail.feature.inventoryFlow.presentation.shell.InventoryPhonePortraitShell
import com.dispensesure.retail.feature.inventoryFlow.presentation.shell.InventoryTabletLandscapeShell
import com.dispensesure.retail.feature.inventoryFlow.presentation.shell.InventoryTabletPortraitShell

/**
 * Entry point for the inventory (Batch Stock Count) flow. Shows the persistent
 * Batch Stock Count panel over the camera; ML/tray detection stay off until the
 * user taps SCAN PILLS, which hands off to DispenseFlowScreen in stock-count
 * mode. Dispatches to the right shell per form factor.
 */
@Composable
fun InventoryFlowScreen(navController: NavController) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    when {
        isTablet && isLandscape -> InventoryTabletLandscapeShell(navController = navController)
        isTablet -> InventoryTabletPortraitShell(navController = navController)
        isLandscape -> InventoryPhoneLandscapeShell(navController = navController)
        else -> InventoryPhonePortraitShell(navController = navController)
    }
}
