package com.dispensesure.retail.core.scanning.engine

import android.graphics.RectF
import com.dispensesure.retail.core.scanning.logic.GloveDetection
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [GloveGate]: when a run of glove frames locks gloves in. */
class GloveGateTest {

    private val gloveGate = GloveGate()

    private val strongGloves = gloveFrame(confidence = 0.9f)
    private val weakGloves = gloveFrame(confidence = 0.5f)

    private fun gloveFrame(confidence: Float, classId: Int = 0) = listOf(
        GloveDetection(RectF(), confidence, classId = classId, className = if (classId == 0) "gloves" else "no_gloves"),
    )

    private fun observeFrames(vararg frames: List<GloveDetection>): List<Boolean> =
        frames.map { gloveDetections -> gloveGate.observe(gloveDetections) }

    @Test
    fun `three strong glove frames in a row lock gloves in on the third`() {
        assertEquals(listOf(false, false, true), observeFrames(strongGloves, strongGloves, strongGloves))
    }

    @Test
    fun `a weak frame breaks the run`() {
        assertEquals(
            listOf(false, false, false, false, false, true),
            observeFrames(strongGloves, strongGloves, weakGloves, strongGloves, strongGloves, strongGloves),
        )
    }

    @Test
    fun `a frame with no detection breaks the run`() {
        assertEquals(
            listOf(false, false, false, false),
            observeFrames(strongGloves, strongGloves, emptyList(), strongGloves),
        )
    }

    @Test
    fun `a strong no_gloves detection does not count`() {
        val strongBareHand = gloveFrame(confidence = 0.95f, classId = 1)

        assertEquals(List(3) { false }, observeFrames(strongBareHand, strongBareHand, strongBareHand))
    }

    @Test
    fun `exactly the minimum confidence counts as strong`() {
        val borderlineGloves = gloveFrame(confidence = 0.75f)

        assertEquals(listOf(false, false, true), observeFrames(borderlineGloves, borderlineGloves, borderlineGloves))
    }

    @Test
    fun `after locking in it reports nothing more`() {
        observeFrames(strongGloves, strongGloves, strongGloves)

        assertEquals(listOf(false, false), observeFrames(strongGloves, strongGloves))
    }

    @Test
    fun `reset starts a new run`() {
        observeFrames(strongGloves, strongGloves, strongGloves)

        gloveGate.reset()

        assertEquals(listOf(false, false, true), observeFrames(strongGloves, strongGloves, strongGloves))
    }
}
