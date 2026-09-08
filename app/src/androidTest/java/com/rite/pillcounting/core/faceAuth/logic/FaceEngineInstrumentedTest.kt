package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requires the real yunet_640x640_float16.tflite.enc + sface_112x112_float16.tflite.enc
 * assets (see the plan's Prerequisite section) and two fixture photos placed at
 * app/src/androidTest/assets/face_fixtures/{same_person_a,same_person_b,other_person}.jpg
 * before this can run. Not part of the default unit test suite.
 */
@RunWith(AndroidJUnit4::class)
class FaceEngineInstrumentedTest {

    @Test
    fun samePersonAcrossTwoPhotosScoresAboveMatchThreshold() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = FaceModelLoader(context)
        val engine = FaceEngine(loader)

        fun embed(assetName: String): FloatArray {
            val bmp = context.assets.open("face_fixtures/$assetName").use { BitmapFactory.decodeStream(it) }
            val face = runBlocking { engine.detectPrimary(bmp) }
            assertNotNull("expected a face in $assetName", face)
            return runBlocking { engine.embed(bmp, face!!) }
        }

        val a = embed("same_person_a.jpg")
        val b = embed("same_person_b.jpg")
        val other = embed("other_person.jpg")

        assertTrue(FaceMatcher.cosine(a, b) >= FaceMatcher.MATCH_THRESHOLD)
        assertTrue(FaceMatcher.cosine(a, other) < FaceMatcher.MATCH_THRESHOLD)
    }
}
