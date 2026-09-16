package com.rite.pillcounting.core.utils.notification

import com.rite.pillcounting.core.utils.logger.AppLogger
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * FirebaseMessagingService delegate class that injects and delegates to FCMService.
 */
@AndroidEntryPoint
class AppFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var fcmService: FCMService
    private val logger = AppLogger.create<AppFirebaseMessagingService>()

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        logger.i("New FCM Token generated: $token")
        // Optionally forward token to backend
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        logger.d("Message received from FCM: ${remoteMessage.data}")

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"]
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"]

        fcmService.showNotification(title, body)
    }
}
