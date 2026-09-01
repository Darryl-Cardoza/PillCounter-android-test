package com.rite.pillcounting.core.utils.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

private const val PREFS_NAME = "permission_prefs"

/**
 * Tracks whether a permission has already been requested once before, so a
 * `false` from [ActivityCompat.shouldShowRequestPermissionRationale] can be
 * told apart from "never asked yet" (both return false) vs. "permanently
 * denied" (also false, but only after a first request+denial happened).
 */
private fun hasRequestedBefore(context: Context, permission: String): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getBoolean(permission, false)
}

/** Call right before requesting [permission] via any API (Compose launcher or classic requestPermissions). */
fun markPermissionRequested(context: Context, permission: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putBoolean(permission, true).apply()
}

/** True once a permission has been denied after already being requested once before — i.e. "Don't ask again". */
fun isPermanentlyDenied(context: Context, permission: String): Boolean {
    val activity = context as? Activity ?: return false
    val granted = ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    if (granted) return false
    val canShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    return !canShowRationale && hasRequestedBefore(context, permission)
}

fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}

/**
 * True when the device-wide location toggle (Settings > Location) is on.
 *
 * Distinct from holding ACCESS_COARSE/FINE_LOCATION: with the permission granted
 * but this switch off, FusedLocationProviderClient resolves every request to
 * null, so [com.rite.pillcounting.core.utils.common.LocationProvider] silently
 * yields "Location unavailable".
 */
fun isLocationServicesEnabled(context: Context): Boolean {
    // isLocationEnabled is API 28; minSdk is 29, so no per-provider fallback.
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return false
    return manager.isLocationEnabled
}

/** Opens the device Location settings page, where the toggle above lives. */
fun openLocationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}

/**
 * Remembers a launcher for a single runtime permission and exposes a
 * `launch()` that also records "this permission has now been requested at
 * least once" so a later denial can be recognized as permanent (see
 * [isPermanentlyDenied]). [onPermanentlyDenied] fires instead of re-prompting
 * once the user has denied it after an earlier request.
 */
@Composable
fun rememberPermissionRequester(
    permission: String,
    onResult: (granted: Boolean) -> Unit,
    onPermanentlyDenied: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            onResult(granted)
            if (!granted && isPermanentlyDenied(context, permission)) {
                onPermanentlyDenied()
            }
        },
    )
    return {
        if (isPermanentlyDenied(context, permission)) {
            onPermanentlyDenied()
        } else {
            markPermissionRequested(context, permission)
            launcher.launch(permission)
        }
    }
}

/**
 * Dialog shown when a permission has been permanently denied ("Don't ask
 * again"), explaining why it's needed and offering a direct jump to the
 * app's Settings > Permissions screen (the only way to re-grant it now).
 */
@Composable
fun PermissionSettingsDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                openAppSettings(context)
            }) { Text("Open Settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}

/**
 * Live state for [rememberPermissionState]: [granted] alone can't tell "still
 * waiting on the system dialog" apart from "user tapped Deny", since both start
 * out `false`. [hasResponded] flips to `true` only once the user (or a prior
 * grant) has actually settled the permission, so callers that need to react to
 * an explicit denial (e.g. navigate away) don't fire before the request even
 * completes.
 */
data class PermissionState(
    val granted: Boolean,
    val hasResponded: Boolean,
    val showingSettingsDialog: Boolean,
)

/**
 * Drop-in replacement for the common "check camera/mic/etc permission, launch
 * the request on first composition, and fall back to Settings once denied
 * permanently" pattern duplicated across the scanning screens.
 *
 * @return whether [permission] is currently granted, updated live as the
 * user responds to the system dialog or returns from Settings.
 */
@Composable
fun rememberPermissionState(permission: String, rationaleTitle: String, rationaleMessage: String): Boolean =
    rememberPermissionStateDetailed(permission, rationaleTitle, rationaleMessage).granted

@Composable
fun rememberPermissionStateDetailed(
    permission: String,
    rationaleTitle: String,
    rationaleMessage: String,
): PermissionState {
    val context = LocalContext.current
    val initiallyGranted = ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(initiallyGranted) }
    var hasResponded by remember { mutableStateOf(initiallyGranted) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val requestPermission = rememberPermissionRequester(
        permission = permission,
        onResult = {
            granted = it
            hasResponded = true
        },
        onPermanentlyDenied = {
            showSettingsDialog = true
            // Permanently denied is a settled outcome too — callers watching
            // hasResponded (e.g. to bounce back when the permission is denied)
            // need to fire even though no fresh launcher result comes in on
            // this path (isPermanentlyDenied short-circuits before launching).
            hasResponded = true
        },
    )

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!granted) requestPermission()
    }

    if (showSettingsDialog) {
        PermissionSettingsDialog(
            title = rationaleTitle,
            message = rationaleMessage,
            onDismiss = { showSettingsDialog = false },
        )
    }

    return PermissionState(
        granted = granted,
        hasResponded = hasResponded,
        showingSettingsDialog = showSettingsDialog,
    )
}
