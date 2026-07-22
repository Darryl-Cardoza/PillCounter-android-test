package com.rite.pillcounting.core.utils.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.geometry.Offset
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [CountCirclePositionPrefs]. Context/SharedPreferences are mocked with MockK
 * since this is plain SharedPreferences access (no Robolectric needed) — behavior of
 * contains()/getFloat()/edit() is simulated to drive every branch.
 */
class CountCirclePositionPrefsTest {

    private val context: Context = mockk()
    private val sharedPreferences: SharedPreferences = mockk()
    private val editor: SharedPreferences.Editor = mockk()

    private fun stub() {
        every {
            context.getSharedPreferences("count_circle_position_prefs", Context.MODE_PRIVATE)
        } returns sharedPreferences
    }

    @Test
    fun `getOffset returns null when neither key is present`() {
        stub()
        every { sharedPreferences.contains("offset_x") } returns false
        every { sharedPreferences.contains("offset_y") } returns false

        val result = CountCirclePositionPrefs.getOffset(context)

        assertNull(result)
    }

    @Test
    fun `getOffset returns null when only x key is present`() {
        stub()
        every { sharedPreferences.contains("offset_x") } returns true
        every { sharedPreferences.contains("offset_y") } returns false

        val result = CountCirclePositionPrefs.getOffset(context)

        assertNull(result)
    }

    @Test
    fun `getOffset returns null when only y key is present`() {
        stub()
        every { sharedPreferences.contains("offset_x") } returns false
        every { sharedPreferences.contains("offset_y") } returns true

        val result = CountCirclePositionPrefs.getOffset(context)

        assertNull(result)
    }

    @Test
    fun `getOffset returns stored offset when both keys present`() {
        stub()
        every { sharedPreferences.contains("offset_x") } returns true
        every { sharedPreferences.contains("offset_y") } returns true
        every { sharedPreferences.getFloat("offset_x", 0f) } returns 12.5f
        every { sharedPreferences.getFloat("offset_y", 0f) } returns -7.25f

        val result = CountCirclePositionPrefs.getOffset(context)

        assertEquals(Offset(12.5f, -7.25f), result)
    }

    @Test
    fun `getOffset returns zero offset when stored values are zero`() {
        stub()
        every { sharedPreferences.contains("offset_x") } returns true
        every { sharedPreferences.contains("offset_y") } returns true
        every { sharedPreferences.getFloat("offset_x", 0f) } returns 0f
        every { sharedPreferences.getFloat("offset_y", 0f) } returns 0f

        val result = CountCirclePositionPrefs.getOffset(context)

        assertEquals(Offset(0f, 0f), result)
    }

    @Test
    fun `setOffset persists x and y floats via editor and applies`() {
        stub()
        every { sharedPreferences.edit() } returns editor
        val xSlot = slot<Float>()
        val ySlot = slot<Float>()
        every { editor.putFloat("offset_x", capture(xSlot)) } returns editor
        every { editor.putFloat("offset_y", capture(ySlot)) } returns editor
        every { editor.apply() } returns Unit

        CountCirclePositionPrefs.setOffset(context, Offset(3.5f, -9.0f))

        assertEquals(3.5f, xSlot.captured)
        assertEquals(-9.0f, ySlot.captured)
        verify { editor.putFloat("offset_x", 3.5f) }
        verify { editor.putFloat("offset_y", -9.0f) }
        verify { editor.apply() }
    }

    @Test
    fun `setOffset handles negative and boundary float values`() {
        stub()
        every { sharedPreferences.edit() } returns editor
        every { editor.putFloat(any(), any()) } returns editor
        every { editor.apply() } returns Unit

        CountCirclePositionPrefs.setOffset(context, Offset(Float.MAX_VALUE, Float.MIN_VALUE))

        verify { editor.putFloat("offset_x", Float.MAX_VALUE) }
        verify { editor.putFloat("offset_y", Float.MIN_VALUE) }
    }
}
