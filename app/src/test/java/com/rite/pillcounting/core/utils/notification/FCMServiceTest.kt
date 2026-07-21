package com.rite.pillcounting.core.utils.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [FCMService.showNotification].
 *
 * Runs under Robolectric because the method exercises real Android framework classes
 * (NotificationManager, NotificationChannel, PendingIntent, NotificationCompat.Builder)
 * that cannot be meaningfully driven with plain MockK mocks - the branch logic (null title/
 * message fallback, channel creation on API 26+) depends on real system service behavior.
 *
 * [FCMService.initFCM], [FCMService.subscribeToTopic] and [FCMService.unsubscribeFromTopic]
 * are NOT covered here: they call `FirebaseMessaging.getInstance()`, a static factory that
 * talks to a real (or Firebase-Robolectric-shadowed) singleton and cannot be substituted via
 * MockK without either PowerMock-style static mocking or a constructor/DI seam that does not
 * currently exist on FCMService. Adding such a seam was out of scope per instructions to avoid
 * modifying production code without strong justification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FCMServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = FCMService(context)

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `shows notification with provided title and message`() {
        service.showNotification("My Title", "My Message")

        val shadowManager = shadowOf(notificationManager())
        val notifications = shadowManager.allNotifications
        assertEquals(1, notifications.size)

        val notification: Notification = notifications[0]
        val shadowNotification = shadowOf(notification)
        assertEquals("My Title", shadowNotification.contentTitle)
        assertEquals("My Message", shadowNotification.contentText)
    }

    @Test
    fun `falls back to default title when title is null`() {
        service.showNotification(null, "Some message")

        val shadowManager = shadowOf(notificationManager())
        val notification = shadowManager.allNotifications[0]
        val shadowNotification = shadowOf(notification)
        assertEquals("App Notification", shadowNotification.contentTitle)
        assertEquals("Some message", shadowNotification.contentText)
    }

    @Test
    fun `falls back to default message when message is null`() {
        service.showNotification("Some title", null)

        val shadowManager = shadowOf(notificationManager())
        val notification = shadowManager.allNotifications[0]
        val shadowNotification = shadowOf(notification)
        assertEquals("Some title", shadowNotification.contentTitle)
        assertEquals("You have a new message", shadowNotification.contentText)
    }

    @Test
    fun `falls back to both defaults when title and message are null`() {
        service.showNotification(null, null)

        val shadowManager = shadowOf(notificationManager())
        val notification = shadowManager.allNotifications[0]
        val shadowNotification = shadowOf(notification)
        assertEquals("App Notification", shadowNotification.contentTitle)
        assertEquals("You have a new message", shadowNotification.contentText)
    }

    @Test
    fun `handles empty string title and message without falling back`() {
        // Empty strings are non-null, so the elvis operator does NOT substitute defaults.
        service.showNotification("", "")

        val shadowManager = shadowOf(notificationManager())
        val notification = shadowManager.allNotifications[0]
        val shadowNotification = shadowOf(notification)
        assertEquals("", shadowNotification.contentTitle)
        assertEquals("", shadowNotification.contentText)
    }

    @Test
    fun `creates notification channel with high importance on API 26+`() {
        service.showNotification("Title", "Message")

        val channel = notificationManager().getNotificationChannel("default_channel_id")
        assertEquals("default_channel_id", channel.id)
        assertEquals("General Notifications", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `notification is auto-cancel and high priority`() {
        service.showNotification("Title", "Message")

        val shadowManager = shadowOf(notificationManager())
        val notification = shadowManager.allNotifications[0]
        assertTrue((notification.flags and Notification.FLAG_AUTO_CANCEL) != 0)
        assertEquals(NotificationManager.IMPORTANCE_HIGH.let { Notification.PRIORITY_HIGH }, notification.priority)
    }

    @Test
    fun `multiple calls post multiple notifications`() {
        // showNotification() ids each notification with System.currentTimeMillis().toInt(),
        // so two calls within the same millisecond collide and the second overwrites the first.
        service.showNotification("First", "One")
        Thread.sleep(2)
        service.showNotification("Second", "Two")

        val shadowManager = shadowOf(notificationManager())
        assertEquals(2, shadowManager.allNotifications.size)
    }
}
