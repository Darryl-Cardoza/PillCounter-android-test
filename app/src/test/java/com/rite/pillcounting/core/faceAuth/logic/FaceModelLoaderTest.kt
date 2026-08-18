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
    fun `getOrLoadInterpreters fails loudly when models cannot be loaded`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = FaceModelLoader(context)

        // Robolectric has no AndroidKeyStore, so decrypting the .enc assets can't
        // succeed here — the load must fail loudly, not hand back broken interpreters.
        var threw = false
        try {
            loader.getOrLoadInterpreters()
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
    }
}
