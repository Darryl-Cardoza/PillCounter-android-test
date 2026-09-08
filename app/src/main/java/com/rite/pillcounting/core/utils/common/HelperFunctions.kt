package com.rite.pillcounting.core.utils.common

import Screen
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.os.Process
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.core.net.toUri
import com.rite.pillcounting.core.room.models.dtos.StatusTypeCount
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.menu.domain.model.CountBuckets
import com.rite.pillcounting.navigation.AUTH_GRAPH_ROUTE
import com.rite.pillcounting.core.security.ImageCrypto
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * A centralized collection of lightweight helper utilities used across UI and ViewModels.
 *
 * Provides functionality for:
 * - Email masking for UI display.
 * - Route resolution during app start.
 * - Dashboard count mapping.
 * - Immersive fullscreen toggling.
 * - Bitmap saving and Play Store navigation.
 */
object HelperFunctions {

    /**
     * Masks an email address by keeping part of the local segment visible and replacing the rest with stars.
     *
     * Example: `"andrew@example.com"` → `"an***@example.com"`
     *
     * @param email The original email.
     * @param showFirst Number of starting characters to keep visible.
     * @param minStars Minimum number of stars after the visible part.
     * @return Masked email string or an empty string if invalid.
     */
    fun maskEmail(email: String?, showFirst: Int = 2, minStars: Int = 3): String {
        if (email.isNullOrBlank()) return ""
        val trimmed = email.trim()
        val atIndex = trimmed.indexOf('@')

        if (atIndex <= 0 || atIndex == trimmed.length - 1) {
            return if (trimmed.length <= showFirst) trimmed
            else trimmed.take(showFirst) + "*".repeat(minStars)
        }

        val local = trimmed.substring(0, atIndex)
        val domain = trimmed.substring(atIndex + 1)
        val visible = local.take(showFirst.coerceAtMost(local.length))
        val starsCount = maxOf(minStars, (local.length - visible.length).coerceAtLeast(minStars))
        return "$visible${"*".repeat(starsCount)}@$domain"
    }

    /**
     * Determines the start navigation route AND clears tokens if the offline threshold has expired.
     *
     * Description:
     * If the user is logged in and the last successful `/health` timestamp is still inside
     * the offline threshold, land on Dashboard. If the threshold has already elapsed (user
     * kept the app closed longer than the offline budget), clear tokens and route to the
     * auth graph so they are forced to re-authenticate.
     *
     * What it does:
     * - Reads `isUserLoggedIn`, `lastHealthCheckedAt`, `offlineSessionThresholdSeconds` from prefs.
     * - Returns `Dashboard` for a logged-in user who is within the threshold window (or has
     *   never yet observed a `/health` response — first-launch flow).
     * - Clears tokens and returns `AUTH_GRAPH_ROUTE` when the threshold has been exceeded.
     *
     * Side effect: on threshold-exceeded, tokens + login flag are cleared before returning.
     *
     * @param preferenceHelper Persistent preference handler.
     * @return Route string — either `Dashboard.route` or `AUTH_GRAPH_ROUTE`.
     *
     * Example Usage:
     * val startDestination = resolveStartDestinationAndClearIfExpired(preferenceHelper)
     */
    fun resolveStartDestinationAndClearIfExpired(preferenceHelper: PreferenceHelper): String {
        if (!preferenceHelper.isUserLoggedIn()) return AUTH_GRAPH_ROUTE
        val lastHealthAt = preferenceHelper.getLastHealthCheckedAt()
        // Fall back to loggedInAt when no /health has ever succeeded on this install, so the
        // expiry clock still ticks from the session start instead of remaining unbounded.
        val anchor = if (lastHealthAt > 0L) lastHealthAt else preferenceHelper.getLoggedInAt()
        if (anchor <= 0L) return Screen.Dashboard.route
        val thresholdMs = preferenceHelper.getOfflineSessionThresholdSeconds() * 1_000L
        val elapsed = System.currentTimeMillis() - anchor
        return if (elapsed > thresholdMs) {
            preferenceHelper.clearTokens()
            preferenceHelper.setUserLoggedIn(false)
            preferenceHelper.clearLoggedInAt()
            AUTH_GRAPH_ROUTE
        } else {
            Screen.Dashboard.route
        }
    }

    /**
     * Maps aggregated status and type counts into [CountBuckets] for dashboard visualization.
     *
     * @param rows List of status-type aggregates.
     * @return Structured [CountBuckets] separating fixed/regular and completed/partial counts.
     */
    fun mapCounts(rows: List<StatusTypeCount>): CountBuckets {
        var fixedCompleted = 0
        var fixedPartial = 0
        var regularCompleted = 0
        var regularPartial = 0

        rows.forEach { row ->
            if (row.isDispense) {
                when (row.status) {
                    CountStatus.COMPLETED -> fixedCompleted = row.cnt
                    CountStatus.PARTIAL -> fixedPartial = row.cnt
                    else -> {}
                }
            } else {
                when (row.status) {
                    CountStatus.COMPLETED -> regularCompleted = row.cnt
                    CountStatus.PARTIAL -> regularPartial = row.cnt
                    else -> {}
                }
            }
        }

        return CountBuckets(
            fixedCompleted = fixedCompleted,
            fixedPartial = fixedPartial,
            regularCompleted = regularCompleted,
            regularPartial = regularPartial
        )
    }

    /** Immediately terminates the app process. */
    fun exitApp() {
        Process.killProcess(Process.myPid())
        exitProcess(1)
    }

    /**
     * Enables full immersive mode (hides system UI and navigation bars).
     *
     * @param activity The current [Activity] context.
     */
    fun enableImmersiveFullscreen(activity: Activity) {
        // Let the activity draw into display cutout areas in any orientation, so
        // immersive content (and overlays like bottom-sheets/drawers) reaches the
        // physical screen edge instead of being letterboxed beside a notch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS.takeIf {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    } ?: android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Draw edge-to-edge so child overlays (Popup-based landscape drawer) can
        // fill the full screen including the inset areas.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /**
     * Opens the app’s Play Store listing, falling back to a web URL if unavailable.
     *
     * @param context Application context.
     */
    fun openPlayStore(context: Context) {
        val packageName = context.packageName
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
            )
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri())
            )
        }
    }

    /**
     * Saves a [Bitmap] as a JPEG file in the app's external pictures directory.
     *
     * @param context Application context.
     * @param bitmap The bitmap to save.
     * @param filename Name of the file to create.
     * @param child Optional subdirectory (default: `"barcodes"`).
     * @return The absolute path of the saved image.
     */
    fun saveBitmapToFile(
        context: Context,
        bitmap: Bitmap,
        filename: String,
        child: String = "barcodes",
        grayscale: Boolean = false
    ): String {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), child)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, filename)
        val toSave = if (grayscale) toGrayscaleBitmap(bitmap) else bitmap
        try {
            val baos = ByteArrayOutputStream()
            toSave.compress(Bitmap.CompressFormat.JPEG, 90, baos)
            val encryptedBytes = ImageCrypto.encrypt(baos.toByteArray())
            FileOutputStream(file).use { out -> out.write(encryptedBytes) }
        } finally {
            if (grayscale) toSave.recycle()
        }
        return file.absolutePath
    }

    private fun toGrayscaleBitmap(src: Bitmap): Bitmap {
        val gray = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val paint = Paint()
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return gray
    }

}
