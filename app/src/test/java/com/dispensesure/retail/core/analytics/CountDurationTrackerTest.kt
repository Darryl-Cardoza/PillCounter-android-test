package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.collect.CountDurationTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountDurationTrackerTest {

    private var now = 1_000_000L
    private fun tracker() = CountDurationTracker { now }

    @Test
    fun `measures elapsed seconds between start and finish`() {
        val tracker = tracker()
        tracker.start(1L)
        now += 90_000L
        assertEquals(90L, tracker.finish(1L))
    }

    @Test
    fun `rounds to the nearest second`() {
        val tracker = tracker()
        tracker.start(1L)
        now += 1_500L
        assertEquals(2L, tracker.finish(1L))
    }

    /** Re-entering a screen for an in-progress count must not restart its timer. */
    @Test
    fun `start is idempotent`() {
        val tracker = tracker()
        tracker.start(1L)
        now += 60_000L
        tracker.start(1L)
        now += 60_000L
        assertEquals(120L, tracker.finish(1L))
    }

    /** A count the process was killed during is reported untimed rather than as zero. */
    @Test
    fun `finish without a start returns null`() {
        assertNull(tracker().finish(42L))
    }

    @Test
    fun `finish twice returns null the second time`() {
        val tracker = tracker()
        tracker.start(1L)
        now += 1_000L
        assertEquals(1L, tracker.finish(1L))
        assertNull(tracker.finish(1L))
    }

    @Test
    fun `duration over the four hour ceiling is discarded`() {
        val tracker = tracker()
        tracker.start(1L)
        now += 4L * 60 * 60 * 1000 + 1_000L
        assertNull(tracker.finish(1L))
    }

    @Test
    fun `duration of exactly four hours is kept`() {
        val tracker = tracker()
        tracker.start(1L)
        now += 4L * 60 * 60 * 1000
        assertEquals(14_400L, tracker.finish(1L))
    }

    @Test
    fun `backward clock move discards the duration`() {
        val tracker = tracker()
        tracker.start(1L)
        now -= 5_000L
        assertNull(tracker.finish(1L))
    }

    @Test
    fun `cancel discards a timer without reporting it`() {
        val tracker = tracker()
        tracker.start(1L)
        tracker.cancel(1L)
        assertNull(tracker.finish(1L))
        assertEquals(0, tracker.pendingCount)
    }

    /** Stock and dispense id sequences overlap, so the keys must not collide. */
    @Test
    fun `stock and dispense ids with the same value are separate timers`() {
        val tracker = tracker()
        tracker.start(7L)
        now += 10_000L
        tracker.start(CountDurationTracker.stockKey(7L))
        now += 20_000L

        assertEquals(30L, tracker.finish(7L))
        assertEquals(20L, tracker.finish(CountDurationTracker.stockKey(7L)))
    }

    @Test
    fun `cap evicts the oldest timer`() {
        val tracker = tracker()
        repeat(200) { tracker.start(it.toLong()) }
        assertEquals(200, tracker.pendingCount)

        tracker.start(999L)

        assertEquals(200, tracker.pendingCount)
        assertNull("oldest timer should have been evicted", tracker.finish(0L))
        assertEquals(0L, tracker.finish(1L))
        assertEquals(0L, tracker.finish(999L))
    }
}
