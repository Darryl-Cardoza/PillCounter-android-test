package com.rite.pillcounting.core.utils.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.rite.pillcounting.MainActivity
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Handles all Firebase Cloud Messaging responsibilities.
 * - Token management
 * - Foreground/Background message handling
 * - Notification display
 */
class FCMService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val logger = AppLogger.create<FCMService>()

    /** Initializes FCM and retrieves device token */
    fun initFCM() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                logger.e("Failed to fetch FCM token", task.exception)
                return@addOnCompleteListener
            }

            val token = task.result
            logger.i("FCM Token retrieved (length=${task.result?.length ?: 0})")

            // Optionally send this token to your backend
        }
    }

    /** Fetches the current FCM registration token, or null if it can't be retrieved. */
    suspend fun getToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                logger.e("Failed to fetch FCM token", task.exception)
                continuation.resume(null)
            }
        }
    }

    /** Subscribe to a specific topic */
    fun subscribeToTopic(topic: String) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    logger.i("Subscribed to topic: $topic")
                } else {
                    logger.w("Failed to subscribe to topic: $topic", task.exception)
                }
            }
    }

    /** Unsubscribe from a topic */
    fun unsubscribeFromTopic(topic: String) {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    logger.i("Unsubscribed from topic: $topic")
                } else {
                    logger.w("Failed to unsubscribe from topic: $topic", task.exception)
                }
            }
    }

    /** Display a simple notification */
    fun showNotification(title: String?, message: String?) {
        val safeTitle = title ?: "App Notification"
        val safeMessage = message ?: "You have a new message"
        val channelId = "default_channel_id"

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "General Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(safeTitle)
            .setContentText(safeMessage)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)

        logger.d("Notification shown → Title: $safeTitle | Message: $safeMessage")
    }
}
