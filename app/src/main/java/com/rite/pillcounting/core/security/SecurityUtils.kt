package com.rite.pillcounting.core.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.provider.Settings
import com.rite.pillcounting.R
import java.io.File
import java.security.MessageDigest

/**
 * Centralized Security Utilities
 *
 * Provides runtime environment validation and tamper detection.
 * Used to prevent debugging, rooting, emulator, and signature tampering.
 */
object SecurityUtils {

    /**
     * Checks the device and app environment for known violations.
     *
     * @param context The application context.
     * @return A list of localized violation messages (empty if all clear).
     */
    fun getSecurityViolations(context: Context): List<String> {
        val violations = mutableListOf<String>()

        if (isAdbEnabled(context)) violations.add(context.getString(R.string.violation_adb_enabled))
        if (isDeviceRooted()) violations.add(context.getString(R.string.violation_rooted))
        if (isDebuggerAttached()) violations.add(context.getString(R.string.violation_debugger_attached))
        if (isRunningOnEmulator()) violations.add(context.getString(R.string.violation_emulator))
        if (isAppDebuggable(context)) violations.add(context.getString(R.string.violation_debuggable_build))
        if (!isSignatureValid(context)) violations.add(context.getString(R.string.violation_signature_mismatch))
        if (!isFromPlayStore(context)) violations.add(context.getString(R.string.violation_not_from_playstore))

        return violations
    }

    /** Developer options or ADB enabled. */
    private fun isAdbEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) == 1
        } catch (_: Exception) {
            false
        }
    }

    /** Root detection based on common 'su' paths. */
    private fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/su"
        )
        return paths.any { File(it).exists() }
    }

    /** Detects if a debugger is currently attached. */
    private fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    /** Basic heuristic emulator detection. */
    private fun isRunningOnEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic") ||
                Build.MODEL.contains("Emulator", true) ||
                Build.MODEL.contains("Android SDK built for x86", true) ||
                Build.MANUFACTURER.contains("Genymotion", true) ||
                (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                Build.PRODUCT == "google_sdk")
    }

    /** Checks if the current build is debuggable (should be false in release). */
    private fun isAppDebuggable(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * Validates the app's signing certificate hash.
     *
     * Replace [EXPECTED_SIGNATURE_HASH] with your actual release key hash.
     */
    private fun isSignatureValid(context: Context): Boolean {
        // Get this value by running against your release APK:
        // keytool -printcert -jarfile app-release.apk
        // Or: apksigner verify --print-certs app-release.apk
        // Copy the "SHA-256" line exactly as shown
        val EXPECTED_SHA256 = "YOUR_RELEASE_SHA256_HERE"

        return try {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures
            }

            signatures?.any { sig ->
                // SHA-256 of the raw DER certificate bytes — matches keytool output
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(sig.toByteArray())  // toByteArray() not toCharsString()
                val actual = digest.joinToString(":") { "%02X".format(it) }
                actual == EXPECTED_SHA256
            } ?: false

        } catch (_: Exception) {
            false
        }
    }

    /** Validates that the app was installed from the Play Store. */
    @Suppress("DEPRECATION")
    private fun isFromPlayStore(context: Context): Boolean {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            installer == "com.android.vending"
        } catch (_: Exception) {
            false
        }
    }
}