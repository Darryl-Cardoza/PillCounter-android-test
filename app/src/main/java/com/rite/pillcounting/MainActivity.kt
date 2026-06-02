package com.rite.pillcounting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.rite.pillcounting.core.security.RuntimeUnit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rite.pillcounting.core.settings.presentation.viewmodel.MainActivityViewModel
import com.rite.pillcounting.core.utils.common.HelperFunctions.enableImmersiveFullscreen
import com.rite.pillcounting.core.utils.common.HelperFunctions.getStartDestination
import com.rite.pillcounting.core.utils.common.HelperFunctions.openPlayStore
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.LoadingIndicator
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.SecurityErrorDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toColor
import com.rite.pillcounting.core.utils.compose.MaintenanceScreen
import com.rite.pillcounting.core.utils.compose.UpdateScreen
import com.rite.pillcounting.core.utils.notification.FCMService
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.navigation.AppNavGraph
import com.rite.pillcounting.ui.theme.ExtendedColors
import com.rite.pillcounting.ui.theme.PillCountingNewModelsTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main entry point of the application.
 *
 * Decides whether to show:
 * - MaintenanceScreen (if backend says maintenance mode is ON)
 * - UpdateScreen (if newer app version required)
 * - AppNavGraph (normal flow)
 *
 * Also performs runtime environment hardening checks.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val settingsViewModel: MainActivityViewModel by viewModels()

    @Inject
    lateinit var fcmService: FCMService

    @Inject lateinit var runtimeUnit: RuntimeUnit

    @Inject
    lateinit var preferenceHelper: PreferenceHelper

    private lateinit var navController: NavController

    private var securityViolations: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Block overlays and screen recording on this window ────────────
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true
        // Keep the screen on while the app is in the foreground. Users running
        // the camera-heavy dispense / pill-count flows would otherwise see the
        // device dim and sleep mid-scan even though they're actively using the
        // screen. This flag is automatically dropped when the activity is no
        // longer visible (home button, app switcher), so it doesn't affect
        // normal locks behavior.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ── Security check — runs once, not on every recomposition ────────────
//        securityViolations = SecurityUtils.getSecurityViolations(this)
//
//        if (securityViolations.isEmpty()) {
            runtimeUnit.grantClearance()
            runtimeUnit.activateIfNeeded()
//        } else {
//            runtimeUnit.revokeClearance()
//        }

        lifecycleScope.launch {
            delay(1500)
            fcmService.initFCM()
            fcmService.subscribeToTopic("global_updates")
        }



        setContent {
            LaunchedEffect(Unit) {
                handleNavigationIntent(intent)
            }

            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            Crossfade(
                targetState = settingsState.isLoading || settingsState.colorSettings == null,
                label = "LoadingOrContent"
            ) { isLoading ->
                if (isLoading) {
                    LoadingIndicator()
                } else {
                    val colorSettings = requireNotNull(settingsState.colorSettings)

                    val lightColorSchemeDynamic = lightColorScheme(
                        primary = colorSettings.light.primary.toColor(),
                        secondary = colorSettings.light.secondary.toColor(),
                        tertiary = colorSettings.light.tertiary.toColor()
                    )

                    val darkColorSchemeDynamic = darkColorScheme(
                        primary = colorSettings.dark.primary.toColor(),
                        secondary = colorSettings.dark.secondary.toColor(),
                        tertiary = colorSettings.dark.tertiary.toColor()
                    )

                    val extendedDynamicLight = ExtendedColors(
                        primaryBackground = colorSettings.light.primaryBackground.toColor(),
                        secondaryBackground = colorSettings.light.secondaryBackground.toColor(),
                        textColor = colorSettings.light.textColor.toColor(),
                        inputBackground = colorSettings.light.inputBackground.toColor(),
                        statusChipBackgroundOnPrimary = colorSettings.light.statusChipBackgroundOnPrimary.toColor(),
                        statusChipBackgroundOnSecondary = colorSettings.light.statusChipBackgroundOnSecondary.toColor()
                    )

                    val extendedDynamicDark = ExtendedColors(
                        primaryBackground = colorSettings.dark.primaryBackground.toColor(),
                        secondaryBackground = colorSettings.dark.secondaryBackground.toColor(),
                        textColor = colorSettings.dark.textColor.toColor(),
                        inputBackground = colorSettings.dark.inputBackground.toColor(),
                        statusChipBackgroundOnPrimary = colorSettings.dark.statusChipBackgroundOnPrimary.toColor(),
                        statusChipBackgroundOnSecondary = colorSettings.dark.statusChipBackgroundOnSecondary.toColor()
                    )

                    PillCountingNewModelsTheme(
                        lightColors         = lightColorSchemeDynamic,
                        darkColors          = darkColorSchemeDynamic,
                        lightExtendedColors = extendedDynamicLight,
                        darkExtendedColors  = extendedDynamicDark
                    ) {
                        navController = rememberNavController()
                        val preferenceHelper = remember { PreferenceHelper(this) }
                        val startDestination = remember { getStartDestination(preferenceHelper) }

                        // ── Security dialog shown once over all other content ──
                        if (securityViolations.isNotEmpty()) {
                            SecurityErrorDialog(securityViolations)
                        } else {
                            when {
                                settingsState.isMaintenanceMode -> MaintenanceScreen()

                                settingsState.isUpdateRequired  -> UpdateScreen(
                                    onUpdateClick = { openPlayStore(this) }
                                )

                                else -> AppNavGraph(
                                    navController    = navController as NavHostController,
                                    startDestination = startDestination,
                                    onLogin          = { settingsViewModel.onUserLoginOrLogOut() },
                                    onLogOut         = { settingsViewModel.onUserLoginOrLogOut() }
                                )
                            }
                        }

                        // Security check overlay if you want:
//                        val violations = SecurityUtils.getSecurityViolations(this)
//                        if (violations.isNotEmpty()) {
//                            SecurityErrorDialog(violations)
//                        }
                    }
                }
            }
        }

        enableImmersiveFullscreen(this)
        requestLocationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun requestLocationPermission() {
        val permissions = mutableListOf<String>()

        // Location permission
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Show rationale if the user has previously denied
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Location Access")
                    .setMessage(getString(R.string.permission_location_rationale))
                    .setPositiveButton("Continue") { _, _ ->
                        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        requestPermissions(permissions)
                    }
                    .setNegativeButton("Not now", null)
                    .show()
                return
            }
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
        }
    }

    private fun requestPermissions(permissions: List<String>) {
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
    }

    private fun handleNavigationIntent(intent: Intent) {
        val route = intent.getStringExtra("navigate_route") ?: return

        navController.navigate(route) {
            launchSingleTop = true

            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }

            restoreState = true
        }

        intent.removeExtra("navigate_route")
    }

}