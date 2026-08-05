package com.rite.pillcounting.navigation

import Screen
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.rite.pillcounting.feature.batchCount.presentation.BatchScreen
import com.rite.pillcounting.feature.dashboard.presentation.DashboardScreen
import com.rite.pillcounting.feature.dispenseFlow.presentation.DispenseFlowScreen
import com.rite.pillcounting.feature.faceAuth.presentation.FaceRegistrationScreen
import com.rite.pillcounting.feature.faceAuth.presentation.FaceUsersListScreen
import com.rite.pillcounting.feature.faceAuth.presentation.FaceVerifyScreen
import com.rite.pillcounting.feature.inventoryFlow.presentation.InventoryFlowScreen
import com.rite.pillcounting.feature.history.domain.model.HistoryMode
import com.rite.pillcounting.feature.history.presentation.BatchHistoryDetailScreen
import com.rite.pillcounting.feature.history.presentation.HistoryDetailScreen
import com.rite.pillcounting.feature.history.presentation.HistoryScreen
import com.rite.pillcounting.feature.menu.presentation.MenuScreen
import com.rite.pillcounting.feature.profile.presentation.ProfileScreen
import com.rite.pillcounting.feature.settings.presentation.SaveCsDoubleCountScreen
import com.rite.pillcounting.feature.settings.presentation.SaveHistoryForScreen
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
            route = Screen.Batch.route,
            arguments = Screen.Batch.navArguments
        ) {
            val previousRoute = navController.previousBackStackEntry?.destination?.route
            BatchScreen(
                navController = navController,
                onBackClick = {
                    navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                }
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

        composable(route = Screen.FaceRegistration.route) {
            FaceRegistrationScreen(navController = navController)
        }

        composable(route = Screen.FaceRecognitionUsers.route) {
            FaceUsersListScreen(navController = navController)
        }

        composable(route = Screen.FaceVerify.route) {
            FaceVerifyScreen(navController = navController)
        }

    }
}