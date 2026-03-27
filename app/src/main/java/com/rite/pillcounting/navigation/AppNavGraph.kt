// file: navigation/AppNavGraph.kt

package com.rite.pillcounting.navigation

import Screen
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import com.rite.pillcounting.feature.barcodeScan.presentation.ScanBarCodeScreen
import com.rite.pillcounting.feature.countResume.presentation.FixedCountResumeScreen
import com.rite.pillcounting.feature.countResume.presentation.RegularCountResumeScreen
import com.rite.pillcounting.feature.dashboard.presentation.DashboardScreen
import com.rite.pillcounting.feature.history.domain.model.HistoryMode
import com.rite.pillcounting.feature.history.presentation.HistoryDetailScreen
import com.rite.pillcounting.feature.history.presentation.HistoryScreen
import com.rite.pillcounting.feature.menu.presentation.MenuScreen
import com.rite.pillcounting.feature.pillCountScan.presentation.PillScanningScreen
import com.rite.pillcounting.feature.profile.presentation.ProfileScreen
import com.rite.pillcounting.feature.settings.presentation.SettingsScreen
import com.rite.pillcounting.feature.unsyncedTransaction.presentation.compose.UnsyncedTransactionScreen

// Define constants for nested graph routes for better organization
const val AUTH_GRAPH_ROUTE = "auth"

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    onLogin: () -> Unit,
    onLogOut: () -> Unit
) {
    NavHost(
        navController = navController, startDestination = startDestination
    ) {
        authGraph(
            navController,
            onLogin = onLogin
        )

        composable(route = Screen.Dashboard.route) {
            DashboardScreen(navController)
        }

        composable(
            route = Screen.ScanBarcode.route, arguments = Screen.ScanBarcode.navArguments,
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "pillcounter://scan/{type}"
                }
            )
        ) { backStackEntry ->
            val scanType = backStackEntry.arguments?.getString(Screen.ScanBarcode.ARG_TYPE) ?: ""
            ScanBarCodeScreen(navController, scanType)
        }

        composable(
            route = Screen.PillCount.route, arguments = Screen.PillCount.navArguments
        ) { backStackEntry ->
            val countType = backStackEntry.arguments?.getString(Screen.PillCount.ARG_TYPE) ?: ""
            PillScanningScreen(navController, countType)
        }


        composable(route = Screen.Menu.route) {
            MenuScreen(navController, onLogOut = onLogOut)
        }

        composable(route = Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }

        composable(route = Screen.ResumeFixedCounts.route) { backStackEntry ->
            FixedCountResumeScreen(
                navController = navController
            )
        }

        composable(route = Screen.ResumeRegularCounts.route) { backStackEntry ->
            RegularCountResumeScreen(
                navController = navController,
            )
        }

        composable(
            route = Screen.History.route,
            arguments = Screen.History.navArguments
        ) { backStackEntry ->

            val historyMode = backStackEntry.arguments
                ?.getString(Screen.History.ARG_TYPE)
                ?.let { runCatching { HistoryMode.valueOf(it) }.getOrNull() }
                ?: HistoryMode.NORMAL

            HistoryScreen(
                navController = navController,
                historyMode = historyMode,
                onBackClick = { navController.popBackStack() }
            )
        }


        composable(route = Screen.HistoryDetail.route) {
            HistoryDetailScreen(
                navController = navController,
            )
        }

        composable(route = Screen.UnsyncedTransactionScreen.route) {
            UnsyncedTransactionScreen(
                navController = navController,
            )
        }

        composable(route = Screen.Profile.route) {
            ProfileScreen(
                navController = navController
            )
        }
    }
}