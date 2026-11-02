package com.dispensesure.retail.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.dispensesure.retail.feature.dashboard.presentation.DashboardScreen
import com.dispensesure.retail.feature.dispense.presentation.DispenseFlowScreen
import com.dispensesure.retail.feature.faceAuth.presentation.FaceIntroScreen
import com.dispensesure.retail.feature.faceAuth.presentation.FaceRegistrationScreen
import com.dispensesure.retail.feature.faceAuth.presentation.FaceUsersListScreen
import com.dispensesure.retail.feature.history.domain.model.HistoryMode
import com.dispensesure.retail.feature.history.presentation.BatchHistoryDetailScreen
import com.dispensesure.retail.feature.history.presentation.HistoryDetailScreen
import com.dispensesure.retail.feature.history.presentation.HistoryScreen
import com.dispensesure.retail.feature.menu.presentation.MenuScreen
import com.dispensesure.retail.feature.profile.presentation.ProfileScreen
import com.dispensesure.retail.feature.settings.presentation.SettingsScreen
import com.dispensesure.retail.feature.stockCount.loose.LooseCountScreen
import com.dispensesure.retail.feature.stockCount.sealed.presentation.StockCountScreen
import com.dispensesure.retail.feature.unsyncedTransaction.presentation.compose.UnsyncedTransactionScreen

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

        // Merged dispense flow (RX + NDC + pill counting on one screen).
        composable(
            route = Screen.DispenseFlow.route, arguments = Screen.DispenseFlow.navArguments
        ) {
            // The view model reads the route's txn id and flags from its SavedStateHandle.
            DispenseFlowScreen(navController = navController)
        }


        composable(route = Screen.Menu.route) {
            MenuScreen(navController, onLogOut = onLogOut)
        }

        composable(route = Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }

        composable(
            route = Screen.StockCount.route,
            arguments = Screen.StockCount.navArguments,
        ) {
            StockCountScreen(navController = navController)
        }

        // Loose-pill count for a stock batch (SCAN PILLS on the sealed-bottle screen).
        composable(
            route = Screen.LooseCount.route,
            arguments = Screen.LooseCount.navArguments,
        ) {
            LooseCountScreen(navController = navController)
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


        composable(
            route = Screen.HistoryDetail.route,
            arguments = Screen.HistoryDetail.navArguments
        ) {
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
