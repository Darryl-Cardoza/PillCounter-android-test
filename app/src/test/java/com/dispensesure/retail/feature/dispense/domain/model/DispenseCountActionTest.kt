package com.dispensesure.retail.feature.dispense.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DispenseCountActionTest {

    @Test
    fun addTapped_carriesTheDetectedCount() {
        assertEquals(5, DispenseCountAction.AddTapped(detectedCount = 5).detectedCount)
    }

    @Test
    fun countDeleted_carriesTheCountId() {
        assertEquals(9L, DispenseCountAction.CountDeleted(countId = 9L).countId)
    }

    @Test
    fun stageChanged_carriesTheStageAndTheHazardFlag() {
        val stageChanged = DispenseCountAction.StageChanged(DispenseStage.COUNTING, isHazardousDrug = true)

        assertEquals(DispenseStage.COUNTING, stageChanged.stage)
        assertTrue(stageChanged.isHazardousDrug)
    }

    @Test
    fun bottleScanned_carriesTheValueAndThePhoto() {
        val bottleScanned = DispenseCountAction.BottleScanned(rawValue = "raw", imagePath = "/scans/bottle.jpg")

        assertEquals("raw", bottleScanned.rawValue)
        assertEquals("/scans/bottle.jpg", bottleScanned.imagePath)
    }

    @Test
    fun noteSaved_carriesTheNote() {
        assertEquals("note", DispenseCountAction.NoteSaved(note = "note").note)
    }

    @Test
    fun dataObjects_areSingletons() {
        assertSame(DispenseCountAction.DoneTapped, DispenseCountAction.DoneTapped)
        assertSame(DispenseCountAction.VialSaveTapped, DispenseCountAction.VialSaveTapped)
        assertSame(DispenseCountAction.SkipBackCountTapped, DispenseCountAction.SkipBackCountTapped)
    }
}
