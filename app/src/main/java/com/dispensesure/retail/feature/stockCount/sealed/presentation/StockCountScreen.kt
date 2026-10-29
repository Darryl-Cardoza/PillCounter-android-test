package com.dispensesure.retail.feature.stockCount.sealed.presentation

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavController
import com.dispensesure.retail.feature.stockCount.sealed.presentation.shell.StockCountPhoneLandscapeShell
import com.dispensesure.retail.feature.stockCount.sealed.presentation.shell.StockCountPhonePortraitShell
import com.dispensesure.retail.feature.stockCount.sealed.presentation.shell.StockCountTabletLandscapeShell
import com.dispensesure.retail.feature.stockCount.sealed.presentation.shell.StockCountTabletPortraitShell

/**
 * Entry point for the inventory (Batch Stock Count) flow. Shows the persistent
 * Batch Stock Count panel over the camera; ML/tray detection stay off until the
 * user taps SCAN PILLS, which opens LooseCountScreen. Dispatches to the right
 * shell per form factor.
 */
@Composable
fun StockCountScreen(navController: NavController) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    when {
        isTablet && isLandscape -> StockCountTabletLandscapeShell(navController = navController)
        isTablet -> StockCountTabletPortraitShell(navController = navController)
        isLandscape -> StockCountPhoneLandscapeShell(navController = navController)
        else -> StockCountPhonePortraitShell(navController = navController)
    }
}
