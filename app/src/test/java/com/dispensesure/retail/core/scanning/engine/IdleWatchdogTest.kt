package com.dispensesure.retail.core.scanning.engine

import com.dispensesure.retail.core.scanning.engine.IdleWatchdog.Companion.IDLE_TIMEOUT_MS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [IdleWatchdog] on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleWatchdogTest {

    private var idleCalls = 0

    private fun TestScope.newWatchdog() = IdleWatchdog(backgroundScope, onIdle = { idleCalls++ })

    @Test
    fun `a full quiet window calls onIdle once, at the timeout`() = runTest {
        newWatchdog().arm()

        advanceTimeBy(IDLE_TIMEOUT_MS - 1)
        assertEquals(0, idleCalls)

        advanceTimeBy(2)
        assertEquals(1, idleCalls)

        // The watch ends when it fires.
        advanceTimeBy(IDLE_TIMEOUT_MS * 2)
        assertEquals(1, idleCalls)
    }

    @Test
    fun `activity pushes the deadline a full window past the check that saw it`() = runTest {
        val idleWatchdog = newWatchdog().apply { arm() }

        advanceTimeBy(32_000)
        idleWatchdog.noteActivity()
        // Seen by the 35 s check, so the pause moves to 35 s + 60 s = 95 s.
        advanceTimeBy(62_999)
        assertEquals(0, idleCalls)

        advanceTimeBy(2)
        assertEquals(1, idleCalls)
    }

    @Test
    fun `stop means onIdle never runs`() = runTest {
        val idleWatchdog = newWatchdog().apply { arm() }

        idleWatchdog.stop()
        advanceTimeBy(IDLE_TIMEOUT_MS * 3)

        assertEquals(0, idleCalls)
    }

    @Test
    fun `arming after it fired watches again`() = runTest {
        val idleWatchdog = newWatchdog().apply { arm() }
        advanceTimeBy(IDLE_TIMEOUT_MS + 1)

        idleWatchdog.arm()
        advanceTimeBy(IDLE_TIMEOUT_MS + 1)

        assertEquals(2, idleCalls)
    }

    @Test
    fun `arming again restarts the window and keeps one watch`() = runTest {
        val idleWatchdog = newWatchdog().apply { arm() }
        advanceTimeBy(50_000)

        idleWatchdog.arm()
        advanceTimeBy(IDLE_TIMEOUT_MS - 1)
        assertEquals(0, idleCalls)

        advanceTimeBy(2)
        assertEquals(1, idleCalls)
    }

    @Test
    fun `activity before arming does not hold off the pause`() = runTest {
        val idleWatchdog = newWatchdog()
        idleWatchdog.noteActivity()

        idleWatchdog.arm()
        advanceTimeBy(IDLE_TIMEOUT_MS + 1)

        assertEquals(1, idleCalls)
    }
}
