package com.rite.pillcounting.navigation

import Screen
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.rite.pillcounting.feature.login.presentation.LoginScreen
import com.rite.pillcounting.feature.otp.presentation.compose.OTPScreen

/**
 * Builds the **Authentication Navigation Graph** that manages
 * all routes related to the user sign-in and verification flow.
 *
 * This nested graph is part of the root navigation hierarchy
 * and includes the following destinations:
 * - [LoginScreen] — initial screen for user credential entry.
 * - [OTPScreen] — screen for OTP verification after login.
 *
 * @param navController The [NavHostController] used to navigate between authentication screens.
 */
fun NavGraphBuilder.authGraph(
    navController: NavHostController,
    onLogin: () -> Unit
) {
    navigation(
        startDestination = Screen.Login.route,
        route = AUTH_GRAPH_ROUTE
    ) {
        composable(route = Screen.Login.route) {
            LoginScreen(navController)
        }

        composable(
            route = Screen.OtpVerify.route,
            arguments = Screen.OtpVerify.navArguments
        ) { backStackEntry ->
            val email = backStackEntry.arguments?.getString(Screen.OtpVerify.ARG_EMAIL) ?: ""

            OTPScreen(
                navController = navController,
                userEmail = email,
                onLogin = { onLogin() }
            )
        }
    }
}
