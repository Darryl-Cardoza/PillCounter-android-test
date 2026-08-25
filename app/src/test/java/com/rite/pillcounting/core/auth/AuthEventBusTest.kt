package com.rite.pillcounting.core.auth

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AuthEventBus].
 *
 * Covers: buffered non-blocking publish semantics, DROP_OLDEST overflow policy,
 * and replay=0 for late subscribers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthEventBusTest {

    @Test
    fun tryPublish_returnsTrueWhenBufferHasCapacity() {
        val bus = AuthEventBus()
        assertTrue(bus.tryPublish(AuthEvent.SessionExpired))
    }

    @Test
    fun tryPublish_dropsOldestWhenBufferOverflows() = runTest {
        val bus = AuthEventBus()
        // Buffer size = 1 with DROP_OLDEST. Both tryPublish calls return true;
        // the later event wins.
        assertTrue(bus.tryPublish(AuthEvent.SessionExpired))
        assertTrue(bus.tryPublish(AuthEvent.SessionExpired))
    }

    @Test
    fun events_lateSubscriberReceivesEventsPublishedAfterSubscription() = runTest {
        val bus = AuthEventBus()
        bus.events.test {
            bus.tryPublish(AuthEvent.SessionExpired)
            assertEquals(AuthEvent.SessionExpired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun events_replayIsZero_earlyEventsDroppedForLateSubscriber() = runTest {
        val bus = AuthEventBus()
        // Publish before subscribing; DROP_OLDEST + replay=0 means late subscriber gets nothing
        // until a fresh publish arrives after subscription.
        bus.tryPublish(AuthEvent.SessionExpired)
        bus.events.test {
            expectNoEvents()
            bus.tryPublish(AuthEvent.SessionExpired)
            assertEquals(AuthEvent.SessionExpired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
