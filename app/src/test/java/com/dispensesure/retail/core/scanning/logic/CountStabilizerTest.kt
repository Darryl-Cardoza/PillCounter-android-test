package com.dispensesure.retail.core.scanning.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class CountStabilizerTest {

    private fun feed(stabilizer: CountStabilizer, vararg counts: Int): Int {
        var last = 0
        for (c in counts) last = stabilizer.update(c)
        return last
    }

    @Test
    fun `update shows the first count immediately instead of latching it`() {
        assertEquals(7, CountStabilizer().update(7))
    }

    @Test
    fun `first acquisition shows on the frame the tracker confirms`() {
        val stabilizer = CountStabilizer()
        // Frame 1: the tracker has spawned tracks but confirmed none yet.
        assertEquals(0, stabilizer.update(0))
        // Frame 2: they confirm, and the number appears straight away.
        assertEquals(20, stabilizer.update(20))
    }

    @Test
    fun `update stays at zero while the raw count is zero`() {
        val stabilizer = CountStabilizer()
        // The tracker returns nothing until it has confirmed a pill.
        assertEquals(0, stabilizer.update(0))
        assertEquals(0, stabilizer.update(0))
    }

    @Test
    fun `update latches a change once a count is already displayed`() {
        val stabilizer = CountStabilizer()
        assertEquals(7, stabilizer.update(7))
        // 9 now has to win the median and then three agreeing frames.
        assertEquals(7, feed(stabilizer, 9, 9))
        assertEquals(9, stabilizer.update(9))
    }

    @Test
    fun `update holds the first acquired value while the median flaps`() {
        val stabilizer = CountStabilizer()
        // 5 is taken immediately; the alternation then keeps the median moving,
        // so the latch never commits 9.
        val displayed = feed(stabilizer, 5, 9, 5, 9, 5, 9, 5, 9)
        assertEquals(5, displayed)
    }

    @Test
    fun `update ignores a single outlier because the median absorbs it`() {
        val stabilizer = CountStabilizer()
        feed(stabilizer, 4, 4, 4)
        assertEquals(4, stabilizer.update(4))
        // One spike frame: median over [4,4,4,4,99] is still 4, so nothing changes.
        assertEquals(4, stabilizer.update(99))
    }

    @Test
    fun `update moves to a sustained new value`() {
        val stabilizer = CountStabilizer()
        feed(stabilizer, 4, 4, 4, 4, 4)
        assertEquals(4, stabilizer.update(4))
        // Median needs three 8s in the window before it flips, then three agreeing frames.
        val displayed = feed(stabilizer, 8, 8, 8, 8, 8, 8)
        assertEquals(8, displayed)
    }

    // The zero bypass re-arms mid-count, so these pin how slow that still is:
    // it skips the latch, not the median.

    @Test
    fun `blanking an established count takes five consecutive empty frames`() {
        val stabilizer = CountStabilizer()
        feed(stabilizer, 20, 20, 20, 20, 20)

        // The window has to fill with zeros before the median turns over.
        assertEquals(20, feed(stabilizer, 0, 0, 0, 0))
        assertEquals(0, stabilizer.update(0))
    }

    @Test
    fun `recovering from a blanked count still waits for the median to leave zero`() {
        val stabilizer = CountStabilizer()
        feed(stabilizer, 20, 20, 20, 20, 20)
        assertEquals(0, feed(stabilizer, 0, 0, 0, 0, 0))

        // Frame 1 is the tracker warm-up; the window is still all zeros, so the
        // median does not move until three 20s are in it.
        assertEquals(0, feed(stabilizer, 0, 20, 20))
        assertEquals(20, stabilizer.update(20))
    }

    @Test
    fun `reset clears the window so the next scene re-acquires from scratch`() {
        val stabilizer = CountStabilizer()
        feed(stabilizer, 6, 6, 6)
        assertEquals(6, stabilizer.update(6))

        stabilizer.reset()

        // 3 is taken straight away: displayed is back to zero, and the window no
        // longer holds the old 6s that would otherwise dominate the median.
        assertEquals(3, stabilizer.update(3))
    }
}
