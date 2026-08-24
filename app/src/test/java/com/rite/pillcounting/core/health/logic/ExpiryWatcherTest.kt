package com.rite.pillcounting.core.health.logic

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ExpiryWatcher].
 *
 * Covers: idempotent start, durable loop across EXPIRED observations, and stopForTest reset.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiryWatcherTest {

    private lateinit var controller: SessionHealthController

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        controller = mockk(relaxed = true)
        ExpiryWatcher.stopForTest()
    }

    @After
    fun tearDown() {
        ExpiryWatcher.stopForTest()
        unmockkAll()
    }

    @Test
    fun start_isIdempotent_secondCallReturnsSameJob() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        every { controller.evaluateExpiry() } returns false

        val first = ExpiryWatcher.start(scope, controller)
        val second = ExpiryWatcher.start(scope, controller)

        assertSame(first, second)
        first.cancelAndJoin()
    }

    @Test
    fun watcher_keepsPollingAfterExpiredIsObserved() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        // Alternating false/true — a true return does NOT stop the loop.
        every { controller.evaluateExpiry() } returnsMany listOf(false, true, false, true, false)

        val job = ExpiryWatcher.start(scope, controller)
        advanceTimeBy(5_500L)  // 5+ ticks

        verify(atLeast = 4) { controller.evaluateExpiry() }
        job.cancelAndJoin()
    }

    @Test
    fun stopForTest_allowsNextStartToCreateFreshJob() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        every { controller.evaluateExpiry() } returns false

        val first = ExpiryWatcher.start(scope, controller)
        first.cancelAndJoin()
        ExpiryWatcher.stopForTest()
        val second = ExpiryWatcher.start(scope, controller)

        assertNotSame(first, second)
        second.cancelAndJoin()
    }
}
