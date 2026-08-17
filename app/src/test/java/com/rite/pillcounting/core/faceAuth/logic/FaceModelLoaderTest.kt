package com.rite.pillcounting.core.faceAuth.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FaceModelLoaderTest {

    @Test
    fun `getOrLoadInterpreters throws when encrypted assets are missing`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = FaceModelLoader(context)

        // No yunet_640x640_float16.tflite.enc / sface_112x112_float16.tflite.enc in test assets
        // (they aren't checked into the repo — see the plan's Prerequisite section) — must fail loudly, not silently.
        var threw = false
        try {
            loader.getOrLoadInterpreters()
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
    }
}
