package com.dispensesure.retail.feature.dispense.domain.model

import android.graphics.Bitmap
import com.dispensesure.retail.core.models.StepState
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DispenseCountActionTest {

    @Test
    fun addTransactionDetailClicked_fullCoverage() {
        val e = DispenseCountAction.AddTransactionDetailClicked(5, StepState.SCAN)
        assertEquals(5, e.filteredCount)
        assertEquals(StepState.SCAN, e.stepType)
        assertTrue(e is DispenseCountAction)
        assertEquals(DispenseCountAction.AddTransactionDetailClicked(5, StepState.SCAN), e)
        assertEquals(
            DispenseCountAction.AddTransactionDetailClicked(5, StepState.SCAN).hashCode(),
            e.hashCode()
        )
        assertNotEquals(e, e.copy(filteredCount = 6))
        assertTrue(e.toString().contains("5"))
        assertEquals(6, e.copy(filteredCount = 6).filteredCount)
        assertEquals(5, e.component1())
        assertEquals(StepState.SCAN, e.component2())
    }

    @Test
    fun transactionDetailDeleted_fullCoverage() {
        val e = DispenseCountAction.TransactionDetailDeleted(99L)
        assertEquals(99L, e.txnDetailId)
        assertEquals(DispenseCountAction.TransactionDetailDeleted(99L), e)
        assertEquals(DispenseCountAction.TransactionDetailDeleted(99L).hashCode(), e.hashCode())
        assertNotEquals(e, e.copy(txnDetailId = 1L))
        assertTrue(e.toString().contains("99"))
        assertEquals(1L, e.copy(txnDetailId = 1L).txnDetailId)
        assertEquals(99L, e.component1())
    }

    @Test
    fun allTransactionDetailsDeleted_fullCoverage() {
        val e = DispenseCountAction.AllTransactionDetailsDeleted(StepState.VIAL)
        assertEquals(StepState.VIAL, e.stepType)
        assertEquals(DispenseCountAction.AllTransactionDetailsDeleted(StepState.VIAL), e)
        assertEquals(
            DispenseCountAction.AllTransactionDetailsDeleted(StepState.VIAL).hashCode(),
            e.hashCode()
        )
        assertNotEquals(e, e.copy(stepType = StepState.SCAN))
        assertTrue(e.toString().contains("VIAL"))
        assertEquals(StepState.SCAN, e.copy(stepType = StepState.SCAN).stepType)
        assertEquals(StepState.VIAL, e.component1())
    }

    @Test
    fun dataObjects_areSingletons() {
        assertTrue(DispenseCountAction.RescanClicked is DispenseCountAction)
        assertTrue(DispenseCountAction.PauseClicked is DispenseCountAction)
        assertTrue(DispenseCountAction.DoneClicked is DispenseCountAction)
        assertTrue(DispenseCountAction.NoteSkip is DispenseCountAction)
        assertSame(DispenseCountAction.RescanClicked, DispenseCountAction.RescanClicked)
        assertSame(DispenseCountAction.PauseClicked, DispenseCountAction.PauseClicked)
        assertSame(DispenseCountAction.DoneClicked, DispenseCountAction.DoneClicked)
        assertSame(DispenseCountAction.NoteSkip, DispenseCountAction.NoteSkip)
    }

    @Test
    fun finalDone_fullCoverage() {
        val e = DispenseCountAction.FinalDone(StepState.SCAN, 12)
        assertEquals(StepState.SCAN, e.stepType)
        assertEquals(12, e.totalCount)
        assertEquals(DispenseCountAction.FinalDone(StepState.SCAN, 12), e)
        assertEquals(DispenseCountAction.FinalDone(StepState.SCAN, 12).hashCode(), e.hashCode())
        assertNotEquals(e, e.copy(totalCount = 0))
        assertTrue(e.toString().contains("12"))
        assertEquals(0, e.copy(totalCount = 0).totalCount)
        assertEquals(StepState.SCAN, e.component1())
        assertEquals(12, e.component2())
    }

    @Test
    fun noteSaved_fullCoverage() {
        val e = DispenseCountAction.NoteSaved("hi")
        assertEquals("hi", e.note)
        assertEquals(DispenseCountAction.NoteSaved("hi"), e)
        assertEquals(DispenseCountAction.NoteSaved("hi").hashCode(), e.hashCode())
        assertNotEquals(e, e.copy(note = "bye"))
        assertTrue(e.toString().contains("hi"))
        assertEquals("bye", e.copy(note = "bye").note)
        assertEquals("hi", e.component1())
    }

    @Test
    fun addVialPhotoInTxn_fullCoverage() {
        // Bitmap is an Android type -> relaxed mock.
        val bmp = mockk<Bitmap>(relaxed = true)
        val e = DispenseCountAction.AddVialPhotoInTxn(7, bmp)
        assertEquals(7, e.filteredCount)
        assertSame(bmp, e.bitmap)
        assertTrue(e is DispenseCountAction)
        // equals/hashCode use identity for the mock bitmap
        assertEquals(DispenseCountAction.AddVialPhotoInTxn(7, bmp), e)
        assertEquals(DispenseCountAction.AddVialPhotoInTxn(7, bmp).hashCode(), e.hashCode())
        assertNotEquals(e, e.copy(filteredCount = 8))
        assertTrue(e.toString().contains("7"))
        assertEquals(8, e.copy(filteredCount = 8).filteredCount)
        assertEquals(7, e.component1())
        assertSame(bmp, e.component2())
    }
}
