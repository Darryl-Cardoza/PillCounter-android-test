package com.dispensesure.retail.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.dispensesure.retail.feature.dashboard.presentation.DashboardScreen
import com.dispensesure.retail.feature.dispenseFlow.presentation.DispenseFlowScreen
import com.dispensesure.retail.feature.faceAuth.presentation.FaceIntroScreen
import com.dispensesure.retail.feature.faceAuth.presentation.FaceRegistrationScreen
import com.dispensesure.retail.feature.faceAuth.presentation.FaceUsersListScreen
import com.dispensesure.retail.feature.inventoryFlow.presentation.InventoryFlowScreen
import com.dispensesure.retail.feature.history.domain.model.HistoryMode
import com.dispensesure.retail.feature.history.presentation.BatchHistoryDetailScreen
import com.dispensesure.retail.feature.history.presentation.HistoryDetailScreen
import com.dispensesure.retail.feature.history.presentation.HistoryScreen
import com.dispensesure.retail.feature.menu.presentation.MenuScreen
import com.dispensesure.retail.feature.profile.presentation.ProfileScreen
import com.dispensesure.retail.feature.settings.presentation.SaveCsDoubleCountScreen
import com.dispensesure.retail.feature.settings.presentation.SaveHistoryForScreen
import com.dispensesure.retail.feature.settings.presentation.SettingsScreen
import com.dispensesure.retail.feature.unsyncedTransaction.presentation.compose.UnsyncedTransactionScreen
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent

// Define constants for nested graph routes for better organization
const val AUTH_GRAPH_ROUTE = "auth"

private val logger = AppLogger("AppNavGraph")

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    onLogin: () -> Unit,
    onLogOut: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        authGraph(
            navController,
            onLogin = onLogin
        )

        composable(route = Screen.Dashboard.route) {
            DashboardScreen(navController)
        }

//        composable(
//            route = Screen.ScanBarcode.route, arguments = Screen.ScanBarcode.navArguments,
//            deepLinks = listOf(
//                navDeepLink {
//                    uriPattern = "pillcounter://scan/{type}/{batch_id}?txn_scan_type={txn_scan_type}"
//                }
//            )
//        ) { backStackEntry ->
//            val scanType = backStackEntry.arguments?.getString(Screen.ScanBarcode.ARG_TYPE) ?: ""
//            val txnScanType = runCatching {
//                ScanType.valueOf(
//                    backStackEntry.arguments?.getString(Screen.ScanBarcode.TXN_SCAN_TYPE).orEmpty()
//                )
//            }.getOrElse {
//                ScanType.BARCODE
//            }
//            val batchId =
//                backStackEntry.arguments?.getLong(Screen.ScanBarcode.ARG_BATCH_ID) ?: 0
//
//            ScanBarCodeScreen(navController, scanType, txnScanType, batchId = batchId)
//        }

//        composable(
//            route = Screen.PillCount.route, arguments = Screen.PillCount.navArguments
//        ) { backStackEntry ->
//            val countType = backStackEntry.arguments?.getString(Screen.PillCount.ARG_TYPE) ?: ""
//            InventoryFlowScreen(navController, countType)
//        }

        // Merged dispense flow (RX + NDC + pill counting on one screen).
        composable(
            route = Screen.DispenseFlow.route, arguments = Screen.DispenseFlow.navArguments
        ) { backStackEntry ->
            val countType = backStackEntry.arguments?.getString(Screen.DispenseFlow.ARG_TYPE) ?: ""
            val fromHl7 = backStackEntry.arguments?.getBoolean(Screen.DispenseFlow.ARG_FROM_HL7) ?: false
            val fromResume = backStackEntry.arguments?.getBoolean(Screen.DispenseFlow.ARG_FROM_RESUME) ?: false
            val batchId = backStackEntry.arguments?.getLong(Screen.DispenseFlow.ARG_BATCH_ID) ?: 0L
            val fromQueue = backStackEntry.arguments?.getBoolean(Screen.DispenseFlow.ARG_FROM_QUEUE) ?: false
            val allowedNdcs = backStackEntry.arguments?.getString(Screen.DispenseFlow.ARG_ALLOWED_NDCS) ?: ""
            DispenseFlowScreen(
                navController = navController,
                countType = countType,
                fromHl7 = fromHl7,
                fromResume = fromResume,
                batchId = batchId,
                fromQueue = fromQueue,
                allowedNdcs = allowedNdcs,
            )
        }


        composable(route = Screen.Menu.route) {
            MenuScreen(navController, onLogOut = onLogOut)
        }

        composable(route = Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }

        composable(
            route = Screen.InventoryScan.route,
            arguments = Screen.InventoryScan.navArguments,
        ) {
            InventoryFlowScreen(navController = navController)
        }

        composable(
            route = Screen.History.route,
            arguments = Screen.History.navArguments
        ) { backStackEntry ->

            val historyModeArg = backStackEntry.arguments?.getString(Screen.History.ARG_TYPE)
            val historyMode = historyModeArg
                ?.let {
                    runCatching { HistoryMode.valueOf(it) }
                        .onFailure { e -> logger.e("Invalid History nav arg '$it' — defaulting to NORMAL", e, event = LogEvent.HISTORY_LOAD_FAILED) }
                        .getOrNull()
                }
                ?: HistoryMode.NORMAL

            HistoryScreen(
                navController = navController,
                historyMode = historyMode,
                onBackClick = {
                    if (navController.previousBackStackEntry != null) navController.popBackStack()
                }
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

        composable(route = Screen.SaveHistoryFor.route) {
            SaveHistoryForScreen(navController = navController)
        }

        composable(route = Screen.RequireDoubleCount.route) {
            SaveCsDoubleCountScreen(navController = navController)
        }

        composable(route = Screen.FaceIntro.route) {
            FaceIntroScreen(
                onSkip = { navController.popBackStack() },
                onGetStarted = {
                    navController.navigate(Screen.FaceRegistration.route) {
                        popUpTo(Screen.FaceIntro.route) { inclusive = true }
                    }
                }
            )
        }

        composable(route = Screen.FaceRegistration.route) {
            FaceRegistrationScreen(navController = navController)
        }

        composable(route = Screen.FaceRecognitionUsers.route) {
            FaceUsersListScreen(navController = navController)
        }

    }
}
