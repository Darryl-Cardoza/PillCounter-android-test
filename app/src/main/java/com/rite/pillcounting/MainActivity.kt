package com.rite.pillcounting

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import com.rite.pillcounting.core.utils.permission.isLocationServicesEnabled
import com.rite.pillcounting.core.utils.permission.isPermanentlyDenied
import com.rite.pillcounting.core.utils.permission.markPermissionRequested
import com.rite.pillcounting.core.utils.permission.openAppSettings
import com.rite.pillcounting.core.utils.permission.openLocationSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hierarchy
import com.rite.pillcounting.core.auth.AuthEvent
import com.rite.pillcounting.core.auth.AuthEventBus
import com.rite.pillcounting.core.faceAuth.logic.SessionLockController
import com.rite.pillcounting.core.health.domain.model.HealthState
import com.rite.pillcounting.core.health.logic.ConnectivityCallback
import com.rite.pillcounting.core.health.logic.ExpiryWatcher
import com.rite.pillcounting.core.health.logic.SessionHealthController
import com.rite.pillcounting.core.security.RuntimeUnit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rite.pillcounting.feature.faceAuth.presentation.SessionLockOverlayScreen
import com.rite.pillcounting.feature.settings.presentation.viewmodel.MainActivityViewModel
import com.rite.pillcounting.core.utils.common.HelperFunctions.enableImmersiveFullscreen
import com.rite.pillcounting.core.utils.common.HelperFunctions.resolveStartDestinationAndClearIfExpired
import com.rite.pillcounting.core.utils.common.HelperFunctions.openPlayStore
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.LoadingIndicator
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.SecurityErrorDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toColor
import com.rite.pillcounting.core.utils.compose.MaintenanceScreen
import com.rite.pillcounting.core.utils.compose.OfflineOverlay
import com.rite.pillcounting.core.utils.compose.UpdateScreen
import com.rite.pillcounting.core.utils.notification.FCMService
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.navigation.AUTH_GRAPH_ROUTE
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

    @Inject
    lateinit var sessionLockController: SessionLockController

    @Inject
    lateinit var sessionHealthController: SessionHealthController

    @Inject
    lateinit var authEventBus: AuthEventBus

    @Inject
    lateinit var connectivityCallback: ConnectivityCallback

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

    // Set on login so the face verify runs once the chain is done. Locking straight
    // away puts the verify camera's permission prompt on top of the chain's dialogs.
    private var lockAfterPermissionChain = false

    // True once the "location services are off" dialog has been shown this process.
    private var locationServicesPromptShown = false

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

        // Re-arm the face lock on every fresh launch, before setContent so the
        // overlay is up on the first frame. Null state only: a config-change or
        // process-death restore must not lock a session the user is mid-way through.
        if (savedInstanceState == null) {
            sessionLockController.onAppLaunch()
        }

        // Both phones and tablets are free to rotate by default.
        // Specific screens (e.g. face registration) lock to portrait on phones via
        // DisposableEffect and restore UNSPECIFIED on exit.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

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

        // Volume keys adjust media while we're foregrounded. Unset, Android
        // sends them to the ring stream whenever nothing is playing.
        volumeControlStream = AudioManager.STREAM_MUSIC

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
                        val startDestination = remember { resolveStartDestinationAndClearIfExpired(preferenceHelper) }

                        // ── Security dialog shown once over all other content ──
                        if (securityViolations.isNotEmpty()) {
                            SecurityErrorDialog(securityViolations)
                        } else {
                            when {
                                settingsState.isMaintenanceMode -> MaintenanceScreen()

                                settingsState.isUpdateRequired  -> UpdateScreen(
                                    onUpdateClick = { openPlayStore(this) }
                                )

                                else -> Box {
                                    // Observe session-health state. On EXPIRED, tear down + nav to Login.
                                    val healthState by sessionHealthController.state.collectAsStateWithLifecycle()
                                    LaunchedEffect(healthState) {
                                        if (healthState == HealthState.EXPIRED) {
                                            performLogoutTeardown()
                                            sessionHealthController.resetAfterExpiry()
                                        }
                                    }
                                    // Observe AuthEventBus. Refresh-token-401 anywhere -> same teardown.
                                    LaunchedEffect(Unit) {
                                        authEventBus.events.collect { event ->
                                            if (event is AuthEvent.SessionExpired) {
                                                performLogoutTeardown()
                                            }
                                        }
                                    }
                                    // Kick /health on foreground/resume via lifecycle observer. Watch
                                    // for offline-threshold expiry with a 1s tick.
                                    LaunchedEffect(Unit) {
                                        ExpiryWatcher.start(lifecycleScope, sessionHealthController)
                                    }
                                    // OFFLINE -> HEALTHY drain lives inside SessionHealthController
                                    // itself — see its init block. MainActivity no longer holds an
                                    // Hl7Repository reference.

                                    // Bump on every HEALTHY -> OFFLINE transition so the
                                    // OfflineOverlay re-expands its message pill on each
                                    // fresh disconnect (see OfflineOverlay#resetKey).
                                    var offlineEnteredAt by remember { mutableStateOf(0L) }
                                    LaunchedEffect(Unit) {
                                        var previous: HealthState = sessionHealthController.state.value
                                        if (previous == HealthState.OFFLINE) {
                                            offlineEnteredAt = System.currentTimeMillis()
                                        }
                                        sessionHealthController.state.collect { current ->
                                            if (previous != HealthState.OFFLINE &&
                                                current == HealthState.OFFLINE
                                            ) {
                                                offlineEnteredAt = System.currentTimeMillis()
                                            }
                                            previous = current
                                        }
                                    }
                                    AppNavGraph(
                                        navController    = navController as NavHostController,
                                        startDestination = startDestination,
                                        onLogin          = {
                                            settingsViewModel.onUserLoginOrLogOut()
                                            // Enrolled operators verify their face before reaching the
                                            // dashboard, but only after the permission chain finishes.
                                            lockAfterPermissionChain = true
                                            if (!permissionChainInProgress) {
                                                permissionChainInProgress = true
                                                requestNotificationPermission()
                                            }
                                        },
                                        onLogOut         = { settingsViewModel.onUserLoginOrLogOut() }
                                    )

                                    // App-wide offline indicator: thin red border + bottom-left
                                    // analog clock that visualises time remaining before the
                                    // offline threshold flips the app to EXPIRED. Sits above
                                    // AppNavGraph so it covers every screen (Login included);
                                    // rendered before SessionLockOverlayScreen so face-lock
                                    // still occludes it when both are active.
                                    if (healthState == HealthState.OFFLINE) {
                                        val lastHealthAt by sessionHealthController.lastHealthAt.collectAsStateWithLifecycle()
                                        val thresholdMs by sessionHealthController.thresholdMs.collectAsStateWithLifecycle()
                                        OfflineOverlay(
                                            lastHealthAt = lastHealthAt,
                                            thresholdMs  = thresholdMs,
                                            resetKey     = offlineEnteredAt,
                                        )
                                    }

                                    val isLocked by sessionLockController.isLocked.collectAsStateWithLifecycle()
                                    val startOnScan by sessionLockController.startOnScan.collectAsStateWithLifecycle()
                                    if (isLocked) {
                                        SessionLockOverlayScreen(
                                            startOnScan = startOnScan,
                                            onUnlocked = { sessionLockController.unlock() }
                                        )
                                    }
                                }
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

    // Resets the idle-lock clock on every touch, app-wide — this is the only
    // hook that can see activity across every screen without threading a
    // callback through each one individually (see SessionLockController).
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        sessionLockController.onUserActivity()
        return super.dispatchTouchEvent(ev)
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
        // Fire /health opportunistically on every foreground/resume so the offline
        // banner can clear and pending sync can be triggered without waiting for a
        // user action. Cheap: /health is on an isolated stack with 15s timeouts.
        if (::sessionHealthController.isInitialized && preferenceHelper.isUserLoggedIn()) {
            sessionHealthController.onForegroundResume()
        }
    }

    override fun onStart() {
        super.onStart()
        // Register a ConnectivityManager callback so the moment Android reports
        // network reachability restored, we fire /health and (on success) drain
        // pending Room-persisted work.
        if (::connectivityCallback.isInitialized) {
            connectivityCallback.register()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::connectivityCallback.isInitialized) {
            connectivityCallback.unregister()
        }
    }

    /**
     * Shared teardown used by both the EXPIRED-state observer and the AuthEventBus
     * SessionExpired observer: clear tokens, drop face-lock, nav to auth graph, surface
     * the standard "Session Expired" toast.
     *
     * Guarded against double-fire by both a controller-level [SessionHealthController.beginTeardown]
     * flag and a current-route check so overlapping fires (e.g. a refresh-401 arriving at
     * the same moment expiry fires) do not toast/nav twice.
     *
     * NOTE: We intentionally do NOT call `settingsViewModel.onUserLoginOrLogOut()` here.
     * Session-expiry is a subset of full logout — the user (usually the same person) is
     * expected to re-authenticate immediately, so resetting UI settings (theme, layout)
     * would be jarring. Full-logout path in AppNavGraph.onLogOut keeps the settings-reset
     * call. If that assumption ever changes, add the call here too.
     */
    private fun performLogoutTeardown() {
        if (!sessionHealthController.beginTeardown()) return
        // currentDestination?.route is always a leaf (login, otp, …); the auth graph route
        // ("auth") only appears in the destination's parent. Walk the hierarchy so a second
        // fire arriving after we've already navigated back to the auth graph is a no-op.
        if (::navController.isInitialized &&
            navController.currentDestination?.hierarchy?.any { it.route == AUTH_GRAPH_ROUTE } == true
        ) {
            sessionHealthController.endTeardown()
            return
        }
        try {
            preferenceHelper.clearTokens()
            preferenceHelper.setUserLoggedIn(false)
            sessionHealthController.markLoggedOut()
            sessionLockController.unlock()
            if (::navController.isInitialized) {
                navController.navigate(AUTH_GRAPH_ROUTE) {
                    popUpTo(0) { inclusive = true }
                }
            }
            showToast(this, R.string.session_expired)
        } finally {
            sessionHealthController.endTeardown()
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
            finishPermissionChain()
        }
    }

    // Chain: notification → camera → location. Each step only launches the system
    // dialog if the permission isn't already granted or permanently denied; either
    // way it always calls into the next step so the chain can't stall. The chain ends
    // in [finishPermissionChain], which surfaces the permanently denied permissions
    // accumulated in [permanentlyDeniedPermissions] once, together, and then offers
    // to switch on device location services if they're off.

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
                finishPermissionChain()
                return
            }
            // Show rationale if the user has previously denied
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.location_access)
                    .setMessage(getString(R.string.permission_location_rationale))
                    .setPositiveButton(R.string.face_registration_continue) { _, _ ->
                        markPermissionRequested(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1001
                        )
                    }
                    .setNegativeButton(R.string.not_now) { _, _ -> finishPermissionChain() }
                    .show()
                return
            }
            markPermissionRequested(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1001
            )
        } else {
            finishPermissionChain()
        }
    }

    private fun permissionLabel(permission: String): String = when (permission) {
        Manifest.permission.CAMERA -> getString(R.string.camera)
        Manifest.permission.POST_NOTIFICATIONS -> getString(R.string.notification)
        Manifest.permission.ACCESS_COARSE_LOCATION -> getString(R.string.location)
        else -> permission
    }

    private fun finishPermissionChain() {
        // Chain always ends here (see chain comment above) — safe place to mark it done.
        permissionChainInProgress = false
        // No-ops when nobody has an enabled face profile, e.g. on a first login.
        if (lockAfterPermissionChain) {
            lockAfterPermissionChain = false
            sessionLockController.lockNow(startOnScan = true)
        }
        if (permanentlyDeniedPermissions.isEmpty()) {
            promptEnableLocationServicesIfNeeded()
            return
        }
        val names = permanentlyDeniedPermissions.distinct().joinToString(", ") { permissionLabel(it) }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required_title))
            .setMessage(getString(R.string.permission_required_combined_message, names))
            .setPositiveButton(R.string.open_settings) { _, _ -> openAppSettings(this) }
            .setNegativeButton(R.string.not_now, null)
            // Dismiss, not button press, so back-press and outside-tap chain too.
            .setOnDismissListener { promptEnableLocationServicesIfNeeded() }
            .show()
    }

    // Offers a jump to the device Location screen when the toggle is off.
    private fun promptEnableLocationServicesIfNeeded() {
        if (locationServicesPromptShown) return
        // Nothing to prompt about until the permission itself is granted.
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        if (isLocationServicesEnabled(this)) return

        locationServicesPromptShown = true
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.location_services_off_title))
            .setMessage(getString(R.string.location_services_off_message))
            .setPositiveButton(R.string.open_settings) { _, _ -> openLocationSettings(this) }
            .setNegativeButton(R.string.not_now, null)
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