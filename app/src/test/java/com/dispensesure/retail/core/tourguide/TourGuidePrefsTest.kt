package com.dispensesure.retail.core.tourguide

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TourGuidePrefsTest {

    @Test
    fun `seen is tracked per user`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(TourGuidePrefs.hasSeen(context, 101L))

        TourGuidePrefs.markSeen(context, 101L)

        assertTrue(TourGuidePrefs.hasSeen(context, 101L))
        assertFalse(TourGuidePrefs.hasSeen(context, 102L))
    }
}
