package com.rite.pillcounting.navigation

import Screen
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.feature.barcodeScan.presentation.ScanBarCodeScreen
import com.rite.pillcounting.feature.batchCount.presentation.BatchScreen
import com.rite.pillcounting.feature.countResume.presentation.FixedCountResumeScreen
import com.rite.pillcounting.feature.countResume.presentation.PartialCountsScreen
import com.rite.pillcounting.feature.countResume.presentation.RegularCountResumeScreen
import com.rite.pillcounting.feature.dashboard.presentation.DashboardScreen
import com.rite.pillcounting.feature.history.domain.model.HistoryMode
import com.rite.pillcounting.feature.history.presentation.BatchHistoryDetailScreen
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
                    uriPattern = "pillcounter://scan/{type}/{batch_id}?txn_scan_type={txn_scan_type}"
                }
            )
        ) { backStackEntry ->
            val scanType = backStackEntry.arguments?.getString(Screen.ScanBarcode.ARG_TYPE) ?: ""
            val txnScanType = runCatching {
                ScanType.valueOf(
                    backStackEntry.arguments?.getString(Screen.ScanBarcode.TXN_SCAN_TYPE).orEmpty()
                )
            }.getOrElse {
                ScanType.BARCODE
            }
            val batchId =
                backStackEntry.arguments?.getLong(Screen.ScanBarcode.ARG_BATCH_ID) ?: 0

            ScanBarCodeScreen(navController, scanType, txnScanType, batchId = batchId)
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

        composable(
            route = Screen.Batch.route,
            arguments = Screen.Batch.navArguments
        ) {
            val previousRoute = navController.previousBackStackEntry?.destination?.route
            BatchScreen(
                navController = navController,
                onBackClick = {
                    if (previousRoute == Screen.PartialCountsScreen.route) {
                        navController.popBackStack(Screen.PartialCountsScreen.route, inclusive = false)
                    } else {
                        navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                    }
                }
            )
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

        composable(
            route = Screen.BatchHistoryDetail.route,
            arguments = Screen.BatchHistoryDetail.navArguments
        ) {
            BatchHistoryDetailScreen(navController = navController)
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

        composable(route = Screen.PartialCountsScreen.route) {
            PartialCountsScreen(navController = navController)
        }


    }
}