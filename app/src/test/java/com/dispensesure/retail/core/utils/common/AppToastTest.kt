package com.dispensesure.retail.core.utils.common

import android.app.Application
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Runs on a plain [Application] so PillCountingApplication's startup (OpenCV, Hilt) is skipped.
 * AppToast is a singleton, so each test ends by stopping its activity, which removes any toast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppToastTest {

    private lateinit var controller: ActivityController<ComponentActivity>

    @Before
    fun setUp() {
        AppToast.register(ApplicationProvider.getApplicationContext())
        controller = Robolectric.buildActivity(ComponentActivity::class.java)
    }

    @After
    fun tearDown() {
        // Walk the activity down from wherever the test left it.
        val state = controller.get().lifecycle.currentState
        if (state == Lifecycle.State.RESUMED) controller.pause()
        if (state.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
        controller.destroy()
    }

    @Test
    fun show_whileActivityResumed_showsMessage() {
        controller.setup()

        AppToast.show("Saved", Toast.LENGTH_SHORT)

        assertEquals("Saved", AppToast.shownMessage)
    }

    @Test
    fun show_withNoResumedActivity_showsOnNextResume() {
        controller.create().start()

        AppToast.show("Session expired", Toast.LENGTH_LONG)
        assertNull(AppToast.shownMessage)

        controller.resume().visible()
        assertEquals("Session expired", AppToast.shownMessage)
    }

    @Test
    fun shortToast_hidesAfter2000ms() {
        controller.setup()

        AppToast.show("Short", Toast.LENGTH_SHORT)

        idleFor(1999)
        assertEquals("Short", AppToast.shownMessage)
        idleFor(1)
        assertNull(AppToast.shownMessage)
    }

    @Test
    fun longToast_hidesAfter3500ms() {
        controller.setup()

        AppToast.show("Long", Toast.LENGTH_LONG)

        idleFor(3499)
        assertEquals("Long", AppToast.shownMessage)
        idleFor(1)
        assertNull(AppToast.shownMessage)
    }

    @Test
    fun secondShow_replacesFirstAndRestartsTimer() {
        controller.setup()

        AppToast.show("First", Toast.LENGTH_SHORT)
        idleFor(1000)
        AppToast.show("Second", Toast.LENGTH_SHORT)
        assertEquals("Second", AppToast.shownMessage)

        // The first toast's timer would have fired at 2000ms.
        idleFor(1500)
        assertEquals("Second", AppToast.shownMessage)
    }

    @Test
    fun pause_keepsToast_stopRemovesIt() {
        controller.setup()
        AppToast.show("Kept", Toast.LENGTH_LONG)

        controller.pause()
        assertEquals("Kept", AppToast.shownMessage)

        controller.stop()
        assertNull(AppToast.shownMessage)
    }

    @Test
    fun pendingToast_olderThan5s_isDroppedOnResume() {
        controller.create().start()

        AppToast.show("Stale", Toast.LENGTH_SHORT)
        idleFor(5001)

        controller.resume().visible()
        assertNull(AppToast.shownMessage)
    }

    @Test
    fun pendingToast_exactly5sOld_showsOnResume() {
        controller.create().start()

        AppToast.show("Just in time", Toast.LENGTH_SHORT)
        idleFor(5000)

        controller.resume().visible()
        assertEquals("Just in time", AppToast.shownMessage)
    }

    @Test
    fun keepUntilShownToast_showsAfterLongBackground() {
        controller.create().start()

        AppToast.show("Session expired", Toast.LENGTH_LONG, keepUntilShown = true)
        idleFor(60_000)

        controller.resume().visible()
        assertEquals("Session expired", AppToast.shownMessage)
    }

    @Test
    fun regularToast_doesNotReplacePendingKeepUntilShownToast() {
        controller.create().start()

        AppToast.show("Session expired", Toast.LENGTH_LONG, keepUntilShown = true)
        AppToast.show("Other", Toast.LENGTH_SHORT)

        controller.resume().visible()
        assertEquals("Session expired", AppToast.shownMessage)
    }

    private fun idleFor(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }
}
