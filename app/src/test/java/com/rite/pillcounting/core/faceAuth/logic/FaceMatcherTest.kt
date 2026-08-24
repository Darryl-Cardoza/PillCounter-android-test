package com.rite.pillcounting.core.faceAuth.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class FaceMatcherTest {

    @Test
    fun `cosine of identical vectors is 1`() {
        val v = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(1f, FaceMatcher.cosine(v, v), 1e-5f)
    }

    @Test
    fun `cosine of opposite vectors is -1`() {
        val v = floatArrayOf(1f, 0f)
        val w = floatArrayOf(-1f, 0f)
        assertEquals(-1f, FaceMatcher.cosine(v, w), 1e-5f)
    }

    @Test
    fun `identify returns the closest gallery entry above threshold`() {
        val probe = floatArrayOf(1f, 0f)
        val gallery = listOf(
            GalleryEntry(faceProfileId = 1L, vec = floatArrayOf(0f, 1f)),   // cosine 0
            GalleryEntry(faceProfileId = 2L, vec = floatArrayOf(0.9f, 0.1f)), // cosine ~0.99
        )
        val result = FaceMatcher.identify(probe, gallery)
        assertEquals(2L, result.faceProfileId)
        assertNotNull(result.faceProfileId)
    }

    @Test
    fun `identify returns null faceProfileId when best score is below threshold`() {
        val probe = floatArrayOf(1f, 0f)
        val gallery = listOf(GalleryEntry(faceProfileId = 1L, vec = floatArrayOf(0f, 1f)))
        val result = FaceMatcher.identify(probe, gallery)
        assertNull(result.faceProfileId)
        assertEquals(0f, result.bestScore, 1e-5f)
    }

    @Test
    fun `identify with empty gallery returns null and zero score`() {
        val result = FaceMatcher.identify(floatArrayOf(1f, 0f), emptyList())
        assertNull(result.faceProfileId)
        assertEquals(0f, result.bestScore, 1e-5f)
    }
}
