package com.rite.pillcounting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import com.rite.pillcounting.core.utils.permission.isPermanentlyDenied
import com.rite.pillcounting.core.utils.permission.markPermissionRequested
import com.rite.pillcounting.core.utils.permission.openAppSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.rite.pillcounting.feature.settings.presentation.viewmodel.MainActivityViewModel
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

    // Permanently-denied permissions collected across the sequential chain below,
    // surfaced as a single combined "Open Settings" dialog once the chain finishes
    // instead of one dialog per permission (all of them redirect to the same
    // Settings screen, so stacking dialogs would just repeat the same action).
    private val permanentlyDeniedPermissions = mutableListOf<String>()

    // True once the initial onCreate chain has run — guards onResume so we don't
    // kick off a second, redundant chain the very first time the activity resumes.
    private var permissionChainStarted = false

    // True while notification → camera → location is actively running. Each
    // launcher.launch() call shows a system dialog, which itself triggers an
    // onPause/onResume of the activity — without this guard, that onResume would
    // re-enter the chain from the top and run a second one concurrently, causing
    // its dialogs to stack on top of the original chain's.
    private var permissionChainInProgress = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && isPermanentlyDenied(this, Manifest.permission.POST_NOTIFICATIONS)) {
            permanentlyDeniedPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestCameraPermission()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && isPermanentlyDenied(this, Manifest.permission.CAMERA)) {
            permanentlyDeniedPermissions.add(Manifest.permission.CAMERA)
        }
        requestLocationPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Block tap-jacking via overlays ────────────────────────────────
        // NOTE: FLAG_SECURE (which blocked screenshots/screen-recording and
        // hid the app on non-secure/cast displays) was intentionally removed
        // to allow screen capture and recording.
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
                                    onLogin          = {
                                        settingsViewModel.onUserLoginOrLogOut()
                                        if (!permissionChainInProgress) {
                                            permissionChainInProgress = true
                                            requestNotificationPermission()
                                        }
                                    },
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
        // Only run the permission chain if the user is already logged in — if the
        // app is about to land on the Login screen, the system/custom permission
        // dialogs would pop up over it (and can stack on top of each other if
        // denied in quick succession). Once login succeeds, onLogin kicks the
        // chain off explicitly instead.
        if (preferenceHelper.isUserLoggedIn()) {
            permissionChainInProgress = true
            requestNotificationPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-run the chain when returning to the app (e.g. from the Settings screen
        // after granting one of several requested permissions) so any permission
        // still missing gets picked back up instead of being stuck until the next
        // cold start. Skip the very first onResume — onCreate already started the
        // chain for that launch. Also skip entirely while not logged in (login screen).
        if (permissionChainStarted) {
            if (preferenceHelper.isUserLoggedIn() && !permissionChainInProgress) {
                permanentlyDeniedPermissions.clear()
                permissionChainInProgress = true
                requestNotificationPermission()
            }
        } else {
            permissionChainStarted = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val locationDenied = grantResults.isEmpty() ||
                grantResults[0] != PackageManager.PERMISSION_GRANTED
            if (locationDenied && isPermanentlyDenied(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                permanentlyDeniedPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            showCombinedSettingsDialogIfNeeded()
        }
    }

    // Chain: notification → camera → location. Each step only launches the system
    // dialog if the permission isn't already granted or permanently denied; either
    // way it always calls into the next step so the chain can't stall. Permanently
    // denied permissions accumulate in [permanentlyDeniedPermissions] and are
    // surfaced once, together, after location (the last step) resolves.

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (isPermanentlyDenied(this, Manifest.permission.POST_NOTIFICATIONS)) {
                permanentlyDeniedPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                requestCameraPermission()
            } else {
                markPermissionRequested(this, Manifest.permission.POST_NOTIFICATIONS)
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            requestCameraPermission()
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (isPermanentlyDenied(this, Manifest.permission.CAMERA)) {
                permanentlyDeniedPermissions.add(Manifest.permission.CAMERA)
                requestLocationPermission()
            } else {
                markPermissionRequested(this, Manifest.permission.CAMERA)
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            requestLocationPermission()
        }
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (isPermanentlyDenied(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                permanentlyDeniedPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                showCombinedSettingsDialogIfNeeded()
                return
            }
            // Show rationale if the user has previously denied
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Location Access")
                    .setMessage(getString(R.string.permission_location_rationale))
                    .setPositiveButton("Continue") { _, _ ->
                        markPermissionRequested(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1001
                        )
                    }
                    .setNegativeButton("Not now") { _, _ -> showCombinedSettingsDialogIfNeeded() }
                    .show()
                return
            }
            markPermissionRequested(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1001
            )
        } else {
            showCombinedSettingsDialogIfNeeded()
        }
    }

    private fun permissionLabel(permission: String) = when (permission) {
        Manifest.permission.CAMERA -> "Camera"
        Manifest.permission.POST_NOTIFICATIONS -> "Notification"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Location"
        else -> permission
    }

    private fun showCombinedSettingsDialogIfNeeded() {
        // Chain always ends here (see chain comment above) — safe place to mark it done.
        permissionChainInProgress = false
        if (permanentlyDeniedPermissions.isEmpty()) return
        val names = permanentlyDeniedPermissions.distinct().joinToString(", ") { permissionLabel(it) }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required_title))
            .setMessage(getString(R.string.permission_required_combined_message, names))
            .setPositiveButton("Open Settings") { _, _ -> openAppSettings(this) }
            .setNegativeButton("Not now", null)
            .show()
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