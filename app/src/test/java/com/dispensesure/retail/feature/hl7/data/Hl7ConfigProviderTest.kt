package com.dispensesure.retail.feature.hl7.data

import android.content.Context
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.util.Hl7Format
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class Hl7ConfigProviderTest {

    private val context: Context = mockk()
    private val preferenceHelper: PreferenceHelper = mockk()
    private val hl7ConfigProvider = Hl7ConfigProvider(context, preferenceHelper)

    @Before
    fun setup() {
        every { context.getString(R.string.app_name) } returns "MobRite"
        every { preferenceHelper.getSelectedTerminalName() } returns "TERMINAL-1"
        every { preferenceHelper.getHl7PmsHost() } returns "PMS-HOST"
        every { preferenceHelper.getHl7Version() } returns "2.5.1"
        every { preferenceHelper.getHl7Format() } returns Hl7Format.DISPENSESURE
    }

    @Test
    fun `the saved settings fill the sender, receiver, version and format`() {
        val config = hl7ConfigProvider.current()

        assertEquals("MobRite", config.sendingApplication)
        assertEquals("TERMINAL-1", config.sendingFacility)
        assertEquals("client", config.receivingApplication)
        assertEquals("PMS-HOST", config.receivingFacility)
        assertEquals("2.5.1", config.versionId)
        assertEquals(Hl7Format.DISPENSESURE, config.messageFormat)
    }

    @Test
    fun `EyeCon sends its own format name as the sending application`() {
        every { preferenceHelper.getHl7Format() } returns Hl7Format.EYECON

        val config = hl7ConfigProvider.current()

        assertEquals("EYECON", config.sendingApplication)
        assertEquals(Hl7Format.EYECON, config.messageFormat)
    }

    @Test
    fun `Vivid sends the app name as the sending application`() {
        every { preferenceHelper.getHl7Format() } returns Hl7Format.VIVID

        val config = hl7ConfigProvider.current()

        assertEquals("MobRite", config.sendingApplication)
        assertEquals(Hl7Format.VIVID, config.messageFormat)
    }

    @Test
    fun `no terminal and a blank PMS host fall back to PILLCOUNTER and PMS`() {
        every { preferenceHelper.getSelectedTerminalName() } returns null
        every { preferenceHelper.getHl7PmsHost() } returns "  "

        val config = hl7ConfigProvider.current()

        assertEquals("PILLCOUNTER", config.sendingFacility)
        assertEquals("PMS", config.receivingFacility)
    }
}
