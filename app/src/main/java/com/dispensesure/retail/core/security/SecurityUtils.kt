package com.dispensesure.retail.core.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import java.io.File
import java.security.MessageDigest

/**
 * Centralized Security Utilities
 *
 * Provides runtime environment validation and tamper detection.
 * Used to prevent debugging, rooting, emulator, and signature tampering.
 */
object SecurityUtils {

    private val logger = AppLogger.create<SecurityUtils>()

    /** Names of the integrity checks, shared by the dialog path, the audit event and the old audit log. */
    object CheckName {
        const val ADB_ENABLED = "ADB_ENABLED"
        const val SCREEN_OVERLAY_ACTIVE = "SCREEN_OVERLAY_ACTIVE"
        const val ROOTED = "ROOTED"
        const val DEBUGGER_ATTACHED = "DEBUGGER_ATTACHED"
        const val EMULATOR = "EMULATOR"
        const val DEBUGGABLE_BUILD = "DEBUGGABLE_BUILD"
        const val SIGNATURE_INVALID = "SIGNATURE_INVALID"
        const val NOT_FROM_PLAYSTORE = "NOT_FROM_PLAYSTORE"
    }

    // libsecurity.so isn't packaged yet; without it ROOTED / DEBUGGER_ATTACHED report "not checked".
    private val isNativeLibraryLoaded: Boolean = try {
        System.loadLibrary("security")
        true
    } catch (e: UnsatisfiedLinkError) {
        logger.w("libsecurity not loaded; native integrity checks skipped", e)
        false
    }

    // JNI declarations
    private external fun nativeIsRooted(): Boolean
    private external fun nativeIsDebuggerAttached(): Boolean

    private var appContext: Context? = null

    /**
     * Checks the device and app environment for known violations.
     *
     * @param context The application context.
     * @return A list of localized violation messages (empty if all clear).
     */
    fun getSecurityViolations(context: Context): List<String> {
        appContext = context.applicationContext
        val violations = mutableListOf<String>()
        val events     = mutableListOf<AuditEvent>()
        val now        = System.currentTimeMillis()

        fun check(
            checkName: String,
            isViolation: Boolean,
            violationStringRes: Int,
            detail: String = ""
        ) {
            events.add(AuditEvent(now, checkName, passed = !isViolation, detail))
            if (isViolation) violations.add(context.getString(violationStringRes))
        }

        check(CheckName.ADB_ENABLED,        isAdbEnabled(context),      R.string.violation_adb_enabled)
        check(CheckName.SCREEN_OVERLAY_ACTIVE,        isScreenOverlayActive(context),      R.string.violation_screen_overlay)
        check(CheckName.ROOTED,             isNativeLibraryLoaded && nativeIsRooted(),            R.string.violation_rooted)
        check(CheckName.DEBUGGER_ATTACHED,  isNativeLibraryLoaded && nativeIsDebuggerAttached(),  R.string.violation_debugger_attached)
        check(CheckName.EMULATOR,           isRunningOnEmulator(),       R.string.violation_emulator)
        check(CheckName.DEBUGGABLE_BUILD,   isAppDebuggable(context),    R.string.violation_debuggable_build)
        check(CheckName.SIGNATURE_INVALID,  !isSignatureValid(context),  R.string.violation_signature_mismatch)
        check(CheckName.NOT_FROM_PLAYSTORE, !isFromPlayStore(context),   R.string.violation_not_from_playstore,
            detail = getInstallerName(context)) // log actual installer for audit trail

        // Write all events in one pass
        SecurityAuditLogger.logAll(context, events)

        return violations
    }

    /** Outcome of [runIntegrityChecks]; names match the checkName values in [getSecurityViolations]. */
    data class IntegrityCheckReport(
        val failedChecks: List<String>,
        val notChecked: List<String>,
        val installer: String
    )

    /** Runs every integrity check for the audit log only: nothing is blocked. Call off the main thread. */
    fun runIntegrityChecks(context: Context): IntegrityCheckReport {
        appContext = context.applicationContext
        val overlayPackages = try {
            overlayAppPackages(context)
        } catch (e: Exception) {
            logger.e("overlayAppPackages() check failed", e, event = LogEvent.SECURITY_CHECK_FAILED)
            emptyList()
        }
        val installer = getInstallerName(context)
        // null = could not run (native library missing).
        val violationByCheckName: Map<String, Boolean?> = linkedMapOf(
            CheckName.ADB_ENABLED to isAdbEnabled(context),
            CheckName.SCREEN_OVERLAY_ACTIVE to overlayPackages.isNotEmpty(),
            CheckName.ROOTED to if (isNativeLibraryLoaded) nativeIsRooted() else null,
            CheckName.DEBUGGER_ATTACHED to if (isNativeLibraryLoaded) nativeIsDebuggerAttached() else null,
            CheckName.EMULATOR to isRunningOnEmulator(),
            CheckName.DEBUGGABLE_BUILD to isAppDebuggable(context),
            CheckName.SIGNATURE_INVALID to !isSignatureValid(context),
            CheckName.NOT_FROM_PLAYSTORE to !isFromPlayStore(context)
        )
        return IntegrityCheckReport(
            failedChecks = violationByCheckName.filterValues { it == true }.keys.toList(),
            notChecked = violationByCheckName.filterValues { it == null }.keys.toList(),
            installer = installer
        )
    }

    // Third-party apps holding SYSTEM_ALERT_WINDOW; shared by the overlay check and runIntegrityChecks.
    private fun overlayAppPackages(context: Context): List<String> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
        return packages.filter { pkg ->
            pkg.packageName != context.packageName &&  // exclude ourselves
                    pkg.requestedPermissions?.contains(
                        android.Manifest.permission.SYSTEM_ALERT_WINDOW
                    ) == true &&
                    pm.checkPermission(
                        android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                        pkg.packageName
                    ) == PackageManager.PERMISSION_GRANTED
        }.map { it.packageName }
    }

    private fun isScreenOverlayActive(context: Context): Boolean {
        // Primary check — is there an active overlay on our window right now?
        // This is the most reliable signal and works on all API levels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE)
                        as android.view.WindowManager
                // If our own app doesn't have overlay permission but
                // Settings.canDrawOverlays returns false for us, another
                // app with the permission may be drawing over us
                val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE)
                        as android.app.AppOpsManager

                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOpsManager.unsafeCheckOpNoThrow(
                        android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                        android.os.Process.myUid(),
                        context.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOpsManager.checkOpNoThrow(
                        android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                        android.os.Process.myUid(),
                        context.packageName
                    )
                }

                // If a third-party app has SYSTEM_ALERT_WINDOW granted, flag it
                val overlayApps = overlayAppPackages(context)

                if (overlayApps.isNotEmpty()) {
                    // Log which apps have overlay permission for audit trail
                    SecurityAuditLogger.log(context, AuditEvent(
                        timestampMs = System.currentTimeMillis(),
                        checkName   = CheckName.SCREEN_OVERLAY_ACTIVE,
                        passed      = false,
                        detail      = overlayApps.joinToString(",")
                    ))
                    return true
                }
            } catch (e: Exception) {
                logger.e("isScreenOverlayActive() check failed", e, event = LogEvent.SECURITY_CHECK_FAILED)
            }
        }
        return false
    }

    // Helper to capture installer name for the audit record
    @Suppress("DEPRECATION")
    private fun getInstallerName(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .installingPackageName ?: "unknown"
            } else {
                context.packageManager
                    .getInstallerPackageName(context.packageName) ?: "unknown"
            }
        } catch (e: Exception) {
            logger.e("getInstallerName() failed", e, event = LogEvent.SECURITY_CHECK_FAILED)
            "unknown"
        }
    }

    /** Developer options or ADB enabled. */
    private fun isAdbEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) == 1
        } catch (e: Exception) {
            logger.e("isAdbEnabled() check failed", e, event = LogEvent.SECURITY_CHECK_FAILED)
            false
        }
    }

    /** Root detection based on common 'su' paths. */
//    private fun isDeviceRooted(): Boolean {
//        val paths = arrayOf(
//            "/system/app/Superuser.apk",
//            "/sbin/su", "/system/bin/su", "/system/xbin/su",
//            "/data/local/xbin/su", "/data/local/bin/su",
//            "/system/sd/xbin/su", "/system/bin/failsafe/su",
//            "/data/local/su"
//        )
//        return paths.any { File(it).exists() }
//    }

    /** Detects if a debugger is currently attached. */
//    private fun isDebuggerAttached(): Boolean {
//        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
//    }

    /** Basic heuristic emulator detection. */
    private fun isRunningOnEmulator(): Boolean {
        val context = appContext
        var score = 0

        // ─── Layer 1: Build props (original check — kept as one signal) ───────────
        if (Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("vbox") ||
            Build.FINGERPRINT.contains("test-keys") ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for x86", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.MANUFACTURER.contains("unknown", ignoreCase = true) ||
            Build.BRAND.startsWith("generic") ||
            Build.DEVICE.startsWith("generic") ||
            Build.PRODUCT == "google_sdk" ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu") ||       // QEMU-based emulator
            Build.HARDWARE.contains("vbox86")          // Genymotion
        ) score++

        // ─── Layer 2: Emulator-specific files and sockets ─────────────────────────
        val emulatorFiles = arrayOf(
            "/dev/socket/qemud",                       // QEMU daemon socket
            "/dev/qemu_pipe",                          // QEMU pipe
            "/system/lib/libc_malloc_debug_qemu.so",   // QEMU malloc debug
            "/sys/qemu_trace",                         // QEMU trace
            "/system/bin/qemu-props",                  // QEMU props binary
            "/dev/socket/genyd",                       // Genymotion
            "/dev/socket/baseband_genyd"               // Genymotion baseband
        )
        if (emulatorFiles.any { File(it).exists() }) score++

        // ─── Layer 3: Sensor absence ──────────────────────────────────────────────
        // Most emulators have no accelerometer or lack multiple real sensors.
        // A real device will almost always have both.
        val sensorManager = context?.getSystemService(Context.SENSOR_SERVICE)
                as? android.hardware.SensorManager
        if (sensorManager != null) {
            val hasAccelerometer = sensorManager.getDefaultSensor(
                android.hardware.Sensor.TYPE_ACCELEROMETER) != null
            val hasGyroscope = sensorManager.getDefaultSensor(
                android.hardware.Sensor.TYPE_GYROSCOPE) != null
            if (!hasAccelerometer && !hasGyroscope) score++
        }

        // ─── Layer 4: CPU ABI mismatch ────────────────────────────────────────────
        // An emulator declaring itself as ARM but running on x86 host
        // will show x86 in /proc/cpuinfo while Build.CPU_ABI says arm
        val cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            val declaredArm = cpuAbi.contains("arm", ignoreCase = true)
            val reportedX86 = cpuInfo.contains("GenuineIntel") ||
                    cpuInfo.contains("AuthenticAMD")
            if (declaredArm && reportedX86) score++
        } catch (e: Exception) {
            logger.e("isRunningOnEmulator() CPU ABI check failed", e, event = LogEvent.SECURITY_CHECK_FAILED)
        }

        // ─── Layer 5: MAC address prefix ──────────────────────────────────────────
        // Android emulator default ethernet MAC starts with 02:00:00
        try {
            val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in networkInterfaces) {
                val mac = intf.hardwareAddress ?: continue
                if (mac.size >= 3 &&
                    mac[0] == 0x02.toByte() &&
                    mac[1] == 0x00.toByte() &&
                    mac[2] == 0x00.toByte()
                ) {
                    score++
                    break
                }
            }
        } catch (e: Exception) {
            logger.e("isRunningOnEmulator() MAC address check failed", e, event = LogEvent.SECURITY_CHECK_FAILED)
        }

        // Require 2+ signals to flag as emulator — reduces false positives
        // on legitimate devices that might trip one check incidentally
        return score >= 2
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
        // SHA-256 of your release signing certificate.
        // Generate with: apksigner verify --print-certs app-release.apk
        // Copy the "Signer #1 certificate SHA-256 digest" line exactly.
        val EXPECTED_SHA256 = "8E:17:6F:32:D4:D8:4F:03:E7:54:DD:B1:7F:42:8B:C8:9A:13:4D:CF:3E:D9:03:EC:F0:28:8E:EC:64:87:FF:CF"

        return try {
            val pm = context.packageManager

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo

                // API 28+ — reject multiple signers
                if (signingInfo?.hasMultipleSigners() == true) return false

                signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )

                // API < 28 — reject if more than one certificate is present
                // Blocks the cert injection attack where an attacker inserts a
                // second cert alongside yours so your real cert still matches
                if ((pkgInfo.signatures?.size ?: 0) > 1) return false

                pkgInfo.signatures
            }

            signatures?.any { sig ->
                val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                val actual = digest.joinToString(":") { "%02X".format(it) }
                MessageDigest.isEqual(actual.toByteArray(), EXPECTED_SHA256.toByteArray())
            } ?: false

        } catch (e: Exception) {
            logger.e("isSignatureValid() check failed", e, event = LogEvent.SECURITY_CHECK_FAILED)
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
        } catch (e: Exception) {
            logger.e("isFromPlayStore() check failed", e, event = LogEvent.SECURITY_CHECK_FAILED)
            false
        }
    }
}
