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
    init {
        System.loadLibrary("security")
    }

    // JNI declarations
    private external fun nativeIsRooted(): Boolean
    private external fun nativeIsDebuggerAttached(): Boolean

    private var appContext: Context? = null

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

        check("ADB_ENABLED",        isAdbEnabled(context),      R.string.violation_adb_enabled)
        check("SCREEN_OVERLAY_ACTIVE",        isScreenOverlayActive(context),      R.string.violation_screen_overlay)
        check("ROOTED",             nativeIsRooted(),            R.string.violation_rooted)
        check("DEBUGGER_ATTACHED",  nativeIsDebuggerAttached(),  R.string.violation_debugger_attached)
        check("EMULATOR",           isRunningOnEmulator(),       R.string.violation_emulator)
        check("DEBUGGABLE_BUILD",   isAppDebuggable(context),    R.string.violation_debuggable_build)
        check("SIGNATURE_INVALID",  !isSignatureValid(context),  R.string.violation_signature_mismatch)
        check("NOT_FROM_PLAYSTORE", !isFromPlayStore(context),   R.string.violation_not_from_playstore,
            detail = getInstallerName(context)) // log actual installer for audit trail

        // Write all events in one pass
        SecurityAuditLogger.logAll(context, events)

        return violations
    }

    private fun isScreenOverlayActive(context: Context): Boolean {
        // Primary check — is there an active overlay on our window right now?
        // This is the most reliable signal and works on all API levels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
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
                val pm = context.packageManager
                val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                }

                val overlayApps = packages.filter { pkg ->
                    pkg.packageName != context.packageName &&  // exclude ourselves
                            pkg.requestedPermissions?.contains(
                                android.Manifest.permission.SYSTEM_ALERT_WINDOW
                            ) == true &&
                            pm.checkPermission(
                                android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                                pkg.packageName
                            ) == PackageManager.PERMISSION_GRANTED
                }

                if (overlayApps.isNotEmpty()) {
                    // Log which apps have overlay permission for audit trail
                    SecurityAuditLogger.log(context, AuditEvent(
                        timestampMs = System.currentTimeMillis(),
                        checkName   = "SCREEN_OVERLAY",
                        passed      = false,
                        detail      = overlayApps.joinToString(",") { it.packageName }
                    ))
                    return true
                }
            } catch (_: Exception) {}
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
        } catch (_: Exception) { "unknown" }
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
        } catch (_: Exception) {}

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
        } catch (_: Exception) {}

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
