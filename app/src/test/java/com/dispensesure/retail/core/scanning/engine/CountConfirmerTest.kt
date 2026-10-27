package com.dispensesure.retail.core.scanning.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [CountConfirmer]: which frames turn a count change into activity. */
class CountConfirmerTest {

    private val countConfirmer = CountConfirmer()

    private fun observeFrames(vararg counts: Int): List<Boolean> =
        counts.map { count -> countConfirmer.observe(count) }

    @Test
    fun `a new count confirms on its third frame in a row`() {
        assertEquals(listOf(false, false, true), observeFrames(11, 11, 11))
    }

    @Test
    fun `a confirmed count that keeps arriving is not confirmed again`() {
        assertEquals(listOf(false, false, true, false, false), observeFrames(11, 11, 11, 11, 11))
    }

    @Test
    fun `flicker between two counts never confirms`() {
        assertEquals(List(6) { false }, observeFrames(11, 12, 11, 12, 11, 12))
    }

    @Test
    fun `a broken run starts counting again`() {
        assertEquals(listOf(false, false, false, false, false, true), observeFrames(11, 11, 12, 11, 11, 11))
    }

    @Test
    fun `a different count confirms after the first one`() {
        assertEquals(listOf(false, false, true, false, false, true), observeFrames(11, 11, 11, 12, 12, 12))
    }

    @Test
    fun `coming back to the confirmed count is not new activity`() {
        assertEquals(
            listOf(false, false, true, false, false, false, false),
            observeFrames(11, 11, 11, 12, 11, 11, 11),
        )
    }

    @Test
    fun `reset lets the same count confirm again`() {
        observeFrames(11, 11, 11)

        countConfirmer.reset()

        assertEquals(listOf(false, false, true), observeFrames(11, 11, 11))
    }
}
