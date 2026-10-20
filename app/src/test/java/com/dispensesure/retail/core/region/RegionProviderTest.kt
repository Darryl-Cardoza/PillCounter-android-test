package com.dispensesure.retail.core.region

import android.content.SharedPreferences
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RegionProviderTest {

    private val preferenceHelper = mockk<PreferenceHelper>(relaxed = true)
    private val regionChangeListener = slot<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Before
    fun setup() {
        every { preferenceHelper.regionKey } returns "key_region"
        every { preferenceHelper.registerOnChangeListener(capture(regionChangeListener)) } returns Unit
    }

    @Test
    fun `starts with the stored region`() {
        every { preferenceHelper.getRegion() } returns Region.CA
        val regionProvider = RegionProvider(preferenceHelper)
        assertEquals(Region.CA, regionProvider.region.value)
        assertEquals(Region.CA, regionProvider.current)
    }

    @Test
    fun `follows the pref when the region key changes`() {
        every { preferenceHelper.getRegion() } returns Region.US
        val regionProvider = RegionProvider(preferenceHelper)

        every { preferenceHelper.getRegion() } returns null
        regionChangeListener.captured.onSharedPreferenceChanged(mockk(), "key_region")

        assertNull(regionProvider.region.value)
    }

    @Test
    fun `ignores changes to other keys`() {
        every { preferenceHelper.getRegion() } returns Region.CA
        val regionProvider = RegionProvider(preferenceHelper)

        every { preferenceHelper.getRegion() } returns Region.US
        regionChangeListener.captured.onSharedPreferenceChanged(mockk(), "access_token")

        assertEquals(Region.CA, regionProvider.region.value)
    }

    @Test
    fun `current falls back to US when nothing is stored`() {
        every { preferenceHelper.getRegion() } returns null
        assertEquals(Region.US, RegionProvider(preferenceHelper).current)
    }
}
