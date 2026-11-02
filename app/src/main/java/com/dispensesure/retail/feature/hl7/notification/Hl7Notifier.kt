package com.dispensesure.retail.feature.hl7.notification


import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dispensesure.retail.MainActivity
import com.dispensesure.retail.navigation.Screen
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Hl7Notifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val channelId = "hl7_events"

    init {
        createChannelIfNeeded()
    }

    private fun createChannelIfNeeded() {
        val channel = NotificationChannel(
            channelId,
            "HL7 Events",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for HL7 messages and events"
        }

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun show(
        title: String,
        message: String,
        // The dispense order the tap opens. Null opens the dispense screen on the Rx step.
        dispenseTxnId: Long? = null,
    ) {
        val route = notificationRoute(dispenseTxnId)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_route", route)
            putExtra(MainActivity.EXTRA_OPENED_FROM_NOTIFICATION, true)
        }

        // One PendingIntent per order. With a shared request code, FLAG_UPDATE_CURRENT
        // points every earlier notification at the newest order.
        val pendingIntent = PendingIntent.getActivity(
            context,
            dispenseTxnId?.toInt() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) return
        }

        val contentView = RemoteViews(
            context.packageName,
            com.dispensesure.retail.R.layout.notification_hl7_content
        ).apply {
            setTextViewText(com.dispensesure.retail.R.id.tv_notification_title, title)
            setTextViewText(com.dispensesure.retail.R.id.tv_notification_body, message)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.dispensesure.retail.R.drawable.logo)
            .setContentTitle(title)
            .setContentText(message)
            .setCustomContentView(contentView)
            .setCustomHeadsUpContentView(contentView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify(System.currentTimeMillis().toInt(), notification)
    }
}

// PMS-originated dispense: deep-link into the merged DispenseFlowScreen
// with fromHl7=true so it hydrates from the pre-created txn and starts
// at the NDC scan step (the RX info is already in the HL7 payload).
// With no order, the plain dispense screen opens on the Rx step.
internal fun notificationRoute(dispenseTxnId: Long?): String =
    if (dispenseTxnId == null) Screen.DispenseFlow.createRoute()
    else Screen.DispenseFlow.createRoute(txnId = dispenseTxnId, fromHl7 = true)


