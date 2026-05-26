package com.rite.pillcounting.feature.hl7.notification


import Screen
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
import com.rite.pillcounting.MainActivity
import com.rite.pillcounting.core.room.models.enums.CountType
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
        message: String
    ) {
        print("notification show")
        // PMS-originated dispense: deep-link into the merged DispenseScanScreen
        // with fromHl7=true so it hydrates from the pre-created txn and starts
        // at the NDC scan step (the RX info is already in the HL7 payload).
        // PMS dispense is always FIXED-count (driven by `targetCount` from HL7).
        val route = Screen.DispenseScan.createRoute(
            scanType = CountType.FIXED.toString(),
            fromHl7 = true,
        )

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_route", route)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                print("notification dont have permission ")
                return
            }
        }

        val contentView = RemoteViews(
            context.packageName,
            com.rite.pillcounting.R.layout.notification_hl7_content
        ).apply {
            setTextViewText(com.rite.pillcounting.R.id.tv_notification_title, title)
            setTextViewText(com.rite.pillcounting.R.id.tv_notification_body, message)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.rite.pillcounting.R.drawable.logo)
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


