package com.rite.pillcounting.feature.hl7.notification

import Screen
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

/**
 * Unit tests for [Hl7Notifier].
 *
 * The project sets `isReturnDefaultValues = true` in testOptions (see app/build.gradle.kts),
 * so unmocked android.* calls return defaults (0 / null) instead of throwing
 * "Method ... not mocked". That lets [Hl7Notifier] be constructed and have show() driven
 * on a plain JVM WITHOUT mocking the bulk of the framework (NotificationChannel,
 * NotificationManager, Intent, PendingIntent, RemoteViews, NotificationCompat.Builder, ...).
 * Those collaborators simply no-op / return defaults, so we only mock what we must
 * actually observe or control:
 *   - [ContextCompat.checkSelfPermission]   -> control the permission branch.
 *   - [NotificationManagerCompat]           -> observe whether notify() is posted.
 *   - Build.VERSION.SDK_INT (via reflection) -> select the TIRAMISU permission branch.
 *
 * Robustness / determinism notes (the reason this rewrite exists):
 *   - We DROP all mockkConstructor usage. mockkConstructor installs a global JVM agent
 *     transform on the constructed class; combined with shared static mocks it is a known
 *     source of cross-test leakage when the full suite runs. The default-value behavior
 *     makes those constructors harmless, so mocking them buys nothing but flakiness.
 *   - Every mockkStatic has a matching unmockkAll() in @After.
 *   - The original Build.VERSION.SDK_INT is captured in @Before and ALWAYS restored in
 *     @After, so no value leaks into other test classes in the suite.
 *
 * COVERED:
 *   (a) show() builds + posts a notification when the permission check passes
 *       (TIRAMISU+ with POST_NOTIFICATIONS granted) -> NotificationManagerCompat.notify(...).
 *   (b) show() on TIRAMISU+ WITHOUT POST_NOTIFICATIONS returns early -> notify() NOT called.
 *   - constructor -> createChannelIfNeeded() runs without throwing.
 *   - The pure-Kotlin route building used inside show().
 */
class Hl7NotifierTest {

    private lateinit var context: Context
    private lateinit var managerCompat: NotificationManagerCompat

    private var originalSdkInt: Int = 0

    @Before
    fun setup() {
        // Capture the real SDK_INT so it can be restored unconditionally in tearDown().
        originalSdkInt = Build.VERSION.SDK_INT
        // Force TIRAMISU so the permission-check branch in show() is reachable & deterministic.
        setSdkInt(Build.VERSION_CODES.TIRAMISU)
        assertSdkInt(Build.VERSION_CODES.TIRAMISU)

        context = mockk(relaxed = true)
        every { context.packageName } returns "pkg"
        // init {} casts getSystemService(NOTIFICATION_SERVICE) to NotificationManager,
        // so it must return an actual NotificationManager (relaxed -> createNotificationChannel no-ops).
        every {
            context.getSystemService(Context.NOTIFICATION_SERVICE)
        } returns mockk<NotificationManager>(relaxed = true)

        // Observe notification posting. NotificationManagerCompat.from(...) is static.
        managerCompat = mockk(relaxed = true)
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns managerCompat

        // NotificationCompat.Builder is androidx (not android.*), so returnDefaultValues does
        // NOT cover it: its real build() delegates to android.app.Notification.Builder, which
        // returns null on the JVM and NPEs. Stub build() to a relaxed Notification so show()
        // can reach the notify() call we want to observe. The fluent setters keep returning the
        // same constructed builder. This is the ONLY constructor mock and is cleared by
        // unmockkAll() in tearDown().
        mockkConstructor(NotificationCompat.Builder::class)
        every { anyConstructed<NotificationCompat.Builder>().setSmallIcon(any<Int>()) } answers { self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setContentTitle(any()) } answers { self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setContentText(any()) } answers { self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setCustomContentView(any()) } answers { self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setCustomHeadsUpContentView(any()) } answers { self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setStyle(any()) } answers { self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setPriority(any()) } answers { self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setContentIntent(any()) } answers { self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setAutoCancel(any()) } answers { self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().build() } returns mockk(relaxed = true)

        // Control the permission gate. Default: GRANTED (overridden in the no-permission test).
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED
    }

    @After
    fun tearDown() {
        // Restore the static-final SDK_INT first so nothing leaks even if a verify failed,
        // then drop ALL mockk static/object/constructor mocks installed by this class.
        setSdkInt(originalSdkInt)
        unmockkAll()
    }

    @Test
    fun `constructor creates the notification channel without throwing`() {
        // init {} -> createChannelIfNeeded() exercises getSystemService + createNotificationChannel.
        // With returnDefaultValues those no-op; we just assert construction succeeds.
        val notifier = Hl7Notifier(context)
        assertNotNull(notifier)
    }

    @Test
    fun `show with permission granted builds notification and notifies`() {
        val notifier = Hl7Notifier(context)

        notifier.show("Title", "Body")

        // Permission was checked, and a notification was posted.
        verify { ContextCompat.checkSelfPermission(eq(context), any()) }
        verify { managerCompat.notify(any<Int>(), any()) }
    }

    @Test
    fun `show without permission on TIRAMISU returns early and does not notify`() {
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_DENIED

        val notifier = Hl7Notifier(context)
        notifier.show("Title", "Body")

        // Early return at `if (!hasPermission) return` -> notify() never reached.
        verify(exactly = 0) { managerCompat.notify(any<Int>(), any()) }
    }

    @Test
    fun `dispense route is built for FIXED count from HL7`() {
        // Sanity check on the pure-Kotlin route building that show() performs internally.
        val route = Screen.DispenseFlow.createRoute(
            scanType = com.rite.pillcounting.core.room.models.enums.CountType.FIXED.toString(),
            fromHl7 = true,
        )
        assertNotNull(route)
        assertTrue(route.contains("FIXED"))
        assertTrue(route.contains("true"))
    }

    /**
     * Overrides the `public static final int Build.VERSION.SDK_INT` deterministically.
     *
     * On JDK 21 there is no pure-Reflection path: `Field.modifiers` is reflection-filtered
     * (NoSuchFieldException) so FINAL can't be stripped, and VarHandle refuses writes to final
     * fields. We therefore use sun.misc.Unsafe (staticFieldBase + staticFieldOffset + putInt)
     * which bypasses the final marker at the memory level. Accessing `theUnsafe` needs
     * `--add-opens=java.base/...` which is configured for unit tests in app/build.gradle.kts.
     * If the override fails, [assertSdkInt] fails the test loudly rather than silently
     * exercising the wrong branch.
     */
    private fun setSdkInt(value: Int) {
        val unsafeField: Field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val unsafeClass = unsafe.javaClass
        val sdkField: Field = Build.VERSION::class.java.getField("SDK_INT")
        val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, sdkField)
        val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, sdkField) as Long
        unsafeClass
            .getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(unsafe, base, offset, value)
    }

    private fun assertSdkInt(expected: Int) {
        // Guards against silent branch mis-selection: if the override didn't take, fail clearly.
        org.junit.Assert.assertEquals(
            "Build.VERSION.SDK_INT override did not take effect",
            expected,
            Build.VERSION.SDK_INT,
        )
    }
}
