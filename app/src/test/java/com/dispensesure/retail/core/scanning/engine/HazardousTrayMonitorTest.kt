package com.dispensesure.retail.core.scanning.engine

import com.dispensesure.retail.core.scanning.logic.TrayColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [HazardousTrayMonitor]: tray colour on screen vs the saved hazardous colour. */
class HazardousTrayMonitorTest {

    private val monitor = HazardousTrayMonitor()

    private fun startHazardousDrug(settingOn: Boolean, savedColor: String?) =
        monitor.configure(isHazardousDrug = true, hazardousSettingOn = settingOn, savedHazardousColor = savedColor)

    private fun startOrdinaryDrug(savedColor: String?) =
        monitor.configure(isHazardousDrug = false, hazardousSettingOn = true, savedHazardousColor = savedColor)

    // ─────────────────────────── detection on / off ───────────────────────────

    @Test
    fun `nothing happens before configure`() {
        assertFalse(monitor.isDetectionEnabled)
        assertEquals(TrayDecision.None, monitor.observe(TrayColor.RED))
    }

    @Test
    fun `configure turns tray colour detection on`() {
        startOrdinaryDrug(savedColor = null)

        assertTrue(monitor.isDetectionEnabled)
    }

    @Test
    fun `a frame with no known tray colour does nothing`() {
        startHazardousDrug(settingOn = false, savedColor = null)

        assertEquals(TrayDecision.None, monitor.observe(null))
    }

    // ─────────────────────────── hazardous drug, no saved colour ───────────────────────────

    @Test
    fun `setting on asks the user about a new colour`() {
        startHazardousDrug(settingOn = true, savedColor = null)

        assertEquals(TrayDecision.AskUser(TrayColor.RED), monitor.observe(TrayColor.RED))
    }

    @Test
    fun `while the popup is open nothing else is asked`() {
        startHazardousDrug(settingOn = true, savedColor = null)
        monitor.observe(TrayColor.RED)

        assertEquals(TrayDecision.None, monitor.observe(TrayColor.RED))
        assertEquals(TrayDecision.None, monitor.observe(TrayColor.BLUE))
    }

    @Test
    fun `setting off saves false once`() {
        startHazardousDrug(settingOn = false, savedColor = null)

        assertEquals(TrayDecision.Save(detected = false), monitor.observe(TrayColor.RED))
        assertEquals(TrayDecision.None, monitor.observe(TrayColor.RED))
    }

    // ─────────────────────────── hazardous drug, saved colour ───────────────────────────

    @Test
    fun `the saved hazardous tray saves true once`() {
        startHazardousDrug(settingOn = true, savedColor = "RED")

        assertEquals(TrayDecision.Save(detected = true), monitor.observe(TrayColor.RED))
        assertEquals(TrayDecision.None, monitor.observe(TrayColor.RED))
    }

    @Test
    fun `setting off, another tray saves false`() {
        startHazardousDrug(settingOn = false, savedColor = "RED")

        assertEquals(TrayDecision.Save(detected = false), monitor.observe(TrayColor.BLUE))
    }

    @Test
    fun `setting on, another tray warns once, then the right tray saves true`() {
        startHazardousDrug(settingOn = true, savedColor = "RED")

        assertEquals(TrayDecision.WarnNotHazardousTray, monitor.observe(TrayColor.BLUE))
        assertEquals(TrayDecision.None, monitor.observe(TrayColor.BLUE))
        assertEquals(TrayDecision.Save(detected = true), monitor.observe(TrayColor.RED))
    }

    // ─────────────────────────── ordinary drug ───────────────────────────

    @Test
    fun `an ordinary drug on the hazardous tray warns once and saves nothing`() {
        startOrdinaryDrug(savedColor = "RED")

        assertEquals(TrayDecision.WarnHazardousTrayInUse, monitor.observe(TrayColor.RED))
        assertEquals(TrayDecision.None, monitor.observe(TrayColor.RED))
    }

    @Test
    fun `an ordinary drug on another tray does nothing`() {
        startOrdinaryDrug(savedColor = "RED")

        assertEquals(TrayDecision.None, monitor.observe(TrayColor.BLUE))
    }

    @Test
    fun `an ordinary drug with no saved colour does nothing`() {
        startOrdinaryDrug(savedColor = null)

        assertEquals(TrayDecision.None, monitor.observe(TrayColor.RED))
    }

    // ─────────────────────────── user's answer ───────────────────────────

    @Test
    fun `answering yes saves true`() {
        startHazardousDrug(settingOn = true, savedColor = null)
        monitor.observe(TrayColor.RED)

        assertEquals(TrayDecision.Save(detected = true), monitor.classify(TrayColor.RED, isHazardous = true))
    }

    @Test
    fun `answering no saves false`() {
        startHazardousDrug(settingOn = true, savedColor = null)
        monitor.observe(TrayColor.RED)

        assertEquals(TrayDecision.Save(detected = false), monitor.classify(TrayColor.RED, isHazardous = false))
    }

    @Test
    fun `a second answer saves nothing`() {
        startHazardousDrug(settingOn = true, savedColor = null)
        monitor.classify(TrayColor.RED, isHazardous = true)

        assertEquals(TrayDecision.None, monitor.classify(TrayColor.RED, isHazardous = true))
    }

    @Test
    fun `configure starts a new transaction that can save again`() {
        startHazardousDrug(settingOn = true, savedColor = "RED")
        monitor.observe(TrayColor.RED)

        startHazardousDrug(settingOn = true, savedColor = "RED")

        assertEquals(TrayDecision.Save(detected = true), monitor.observe(TrayColor.RED))
    }

    // ─────────────────────────── idle pause and resume ───────────────────────────

    @Test
    fun `pauseForIdle stops detection`() {
        startHazardousDrug(settingOn = false, savedColor = null)

        monitor.pauseForIdle()

        assertFalse(monitor.isDetectionEnabled)
        assertEquals(TrayDecision.None, monitor.observe(TrayColor.RED))
    }

    @Test
    fun `resume asks again about a colour left unanswered`() {
        startHazardousDrug(settingOn = true, savedColor = null)
        monitor.observe(TrayColor.RED)
        monitor.pauseForIdle()

        monitor.resumeAfterIdle(hazardousSettingOn = true, savedHazardousColor = null)

        assertTrue(monitor.isDetectionEnabled)
        assertEquals(TrayDecision.AskUser(TrayColor.RED), monitor.observe(TrayColor.RED))
    }

    @Test
    fun `resume keeps a result already saved`() {
        startHazardousDrug(settingOn = false, savedColor = null)
        monitor.observe(TrayColor.RED)
        monitor.pauseForIdle()

        monitor.resumeAfterIdle(hazardousSettingOn = false, savedHazardousColor = null)

        assertEquals(TrayDecision.None, monitor.observe(TrayColor.RED))
    }

    @Test
    fun `resume lets the hazardous tray warning show again`() {
        startOrdinaryDrug(savedColor = "RED")
        monitor.observe(TrayColor.RED)
        monitor.pauseForIdle()

        monitor.resumeAfterIdle(hazardousSettingOn = true, savedHazardousColor = "RED")

        assertEquals(TrayDecision.WarnHazardousTrayInUse, monitor.observe(TrayColor.RED))
    }

    @Test
    fun `resume reads the setting again`() {
        startHazardousDrug(settingOn = false, savedColor = null)
        monitor.pauseForIdle()

        monitor.resumeAfterIdle(hazardousSettingOn = true, savedHazardousColor = null)

        assertEquals(TrayDecision.AskUser(TrayColor.RED), monitor.observe(TrayColor.RED))
    }
}
