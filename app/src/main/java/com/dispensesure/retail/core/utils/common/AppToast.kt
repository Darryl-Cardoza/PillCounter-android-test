package com.dispensesure.retail.core.utils.common

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveSp
import com.dispensesure.retail.ui.theme.ToastBackground
import com.dispensesure.retail.ui.theme.ToastText
import java.lang.ref.WeakReference

/**
 * Toast drawn by the app, so Android 12+ can't add the app icon to it. It is its own window
 * on top of the current activity, so it shows over open dialogs, and is removed when that
 * activity stops, so the window never outlives it.
 */
@SuppressLint("StaticFieldLeak")
object AppToast {

    private const val SHORT_MS = 2000L
    private const val LONG_MS = 3500L
    private const val BOTTOM_OFFSET_DP = 64
    private const val PENDING_MAX_AGE_MS = 5000L

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    private var resumedActivity: WeakReference<ComponentActivity>? = null
    private var shownView: View? = null
    private var shownOn: Activity? = null
    private var pendingToast: PendingToast? = null

    /** Message on screen right now, or null. Read by tests. */
    @VisibleForTesting
    internal var shownMessage: String? = null
        private set

    private class PendingToast(
        val message: String,
        val duration: Int,
        val keepUntilShown: Boolean,
        val queuedAt: Long = SystemClock.elapsedRealtime()
    )

    /** Call once from Application.onCreate so the toast knows which activity is on screen. */
    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is ComponentActivity) return
                resumedActivity = WeakReference(activity)
                // A toast asked for while nothing was on screen shows now.
                pendingToast?.let {
                    pendingToast = null
                    // Too old to make sense now, unless it must always be shown.
                    if (it.keepUntilShown || SystemClock.elapsedRealtime() - it.queuedAt <= PENDING_MAX_AGE_MS) {
                        show(it.message, it.duration)
                    }
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }

            // Stop always comes before destroy, so hiding here means no window is leaked.
            override fun onActivityStopped(activity: Activity) {
                if (shownOn === activity) hide()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Shows [message], replacing any toast already on screen. Kept for the next resume if no activity is resumed.
     * A kept toast is dropped after 5s unless [keepUntilShown], and only a [keepUntilShown] toast can replace that one.
     */
    fun show(message: String, duration: Int, keepUntilShown: Boolean = false) {
        hide()
        val activity = resumedActivity?.get()
        if (activity == null) {
            if (pendingToast?.keepUntilShown == true && !keepUntilShown) return
            pendingToast = PendingToast(message, duration, keepUntilShown)
            return
        }

        val view = ComposeView(activity).apply {
            // A separate window has no owners of its own; borrow the activity's so Compose can run.
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setContent { ToastPill(message) }
        }
        activity.windowManager.addView(view, layoutParams(activity))
        shownView = view
        shownOn = activity
        shownMessage = message

        announce(activity, message)
        handler.postDelayed(hideRunnable, if (duration == Toast.LENGTH_LONG) LONG_MS else SHORT_MS)
    }

    private fun hide() {
        handler.removeCallbacks(hideRunnable)
        val view = shownView ?: return
        shownOn?.windowManager?.removeView(view)
        shownView = null
        shownOn = null
        shownMessage = null
    }

    // Added after any open dialog, so it sits on top; taps pass straight through it.
    private fun layoutParams(activity: Activity) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = (BOTTOM_OFFSET_DP * activity.resources.displayMetrics.density).toInt()
        windowAnimations = android.R.style.Animation_Toast
        title = "AppToast"
    }

    // Same event a system Toast sends, so TalkBack reads the message aloud.
    @Suppress("DEPRECATION")
    private fun announce(activity: Activity, message: String) {
        val manager = activity.getSystemService(AccessibilityManager::class.java) ?: return
        if (!manager.isEnabled) return
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED).apply {
            className = Toast::class.java.name
            packageName = activity.packageName
            text.add(message)
        }
        manager.sendAccessibilityEvent(event)
    }
}

/** Dark rounded pill with white text — the system toast look, minus the icon. */
@Composable
private fun ToastPill(message: String) {
    Text(
        text = message,
        color = ToastText,
        fontSize = responsiveSp(8.sp),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .background(ToastBackground, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
