package com.rite.pillcounting.core.scanning.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class CountStabilizerTest {

    private fun feed(stabilizer: CountStabilizer, vararg counts: Int): Int {
        var last = 0
        for (c in counts) last = stabilizer.update(c)
        return last
    }

    @Test
    fun `update starts at zero`() {
        assertEquals(0, CountStabilizer().update(7))
    }

    @Test
    fun `update commits a new value after three agreeing medians`() {
        val stabilizer = CountStabilizer()
        // Window fills with 7s; the median is 7 from the first frame onward.
        assertEquals(0, stabilizer.update(7))
        assertEquals(0, stabilizer.update(7))
        assertEquals(7, stabilizer.update(7))
    }

    @Test
    fun `update holds the displayed value while the median flaps`() {
        val stabilizer = CountStabilizer()
        // Alternating raw counts keep the median moving, so nothing ever commits.
        val displayed = feed(stabilizer, 5, 9, 5, 9, 5, 9, 5, 9)
        assertEquals(0, displayed)
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

    @Test
    fun `reset returns the displayed value to zero and clears the window`() {
        val stabilizer = CountStabilizer()
        feed(stabilizer, 6, 6, 6)
        assertEquals(6, stabilizer.update(6))
        stabilizer.reset()
        assertEquals(0, stabilizer.update(6))
    }
}
