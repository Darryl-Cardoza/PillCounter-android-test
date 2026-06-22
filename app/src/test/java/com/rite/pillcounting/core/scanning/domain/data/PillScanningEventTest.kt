package com.rite.pillcounting.core.scanning.domain.data

import android.graphics.Bitmap
import com.rite.pillcounting.core.models.StepState
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PillScanningEventTest {

    @Test
    fun addTransactionDetailClicked_fullCoverage() {
        val e = PillScanningEvent.AddTransactionDetailClicked(5, StepState.SCAN)
        assertEquals(5, e.filteredCount)
        assertEquals(StepState.SCAN, e.stepType)
        assertTrue(e is PillScanningEvent)
        assertEquals(PillScanningEvent.AddTransactionDetailClicked(5, StepState.SCAN), e)
        assertEquals(
            PillScanningEvent.AddTransactionDetailClicked(5, StepState.SCAN).hashCode(),
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
        val e = PillScanningEvent.TransactionDetailDeleted(99L)
        assertEquals(99L, e.txnDetailId)
        assertEquals(PillScanningEvent.TransactionDetailDeleted(99L), e)
        assertEquals(PillScanningEvent.TransactionDetailDeleted(99L).hashCode(), e.hashCode())
        assertNotEquals(e, e.copy(txnDetailId = 1L))
        assertTrue(e.toString().contains("99"))
        assertEquals(1L, e.copy(txnDetailId = 1L).txnDetailId)
        assertEquals(99L, e.component1())
    }

    @Test
    fun allTransactionDetailsDeleted_fullCoverage() {
        val e = PillScanningEvent.AllTransactionDetailsDeleted(StepState.VIAL)
        assertEquals(StepState.VIAL, e.stepType)
        assertEquals(PillScanningEvent.AllTransactionDetailsDeleted(StepState.VIAL), e)
        assertEquals(
            PillScanningEvent.AllTransactionDetailsDeleted(StepState.VIAL).hashCode(),
            e.hashCode()
        )
        assertNotEquals(e, e.copy(stepType = StepState.SCAN))
        assertTrue(e.toString().contains("VIAL"))
        assertEquals(StepState.SCAN, e.copy(stepType = StepState.SCAN).stepType)
        assertEquals(StepState.VIAL, e.component1())
    }

    @Test
    fun dataObjects_areSingletons() {
        assertTrue(PillScanningEvent.RescanClicked is PillScanningEvent)
        assertTrue(PillScanningEvent.PauseClicked is PillScanningEvent)
        assertTrue(PillScanningEvent.DoneClicked is PillScanningEvent)
        assertTrue(PillScanningEvent.ConfirmDone is PillScanningEvent)
        assertTrue(PillScanningEvent.CancelDone is PillScanningEvent)
        assertTrue(PillScanningEvent.NoteSkip is PillScanningEvent)
        assertSame(PillScanningEvent.RescanClicked, PillScanningEvent.RescanClicked)
        assertSame(PillScanningEvent.PauseClicked, PillScanningEvent.PauseClicked)
        assertSame(PillScanningEvent.DoneClicked, PillScanningEvent.DoneClicked)
        assertSame(PillScanningEvent.ConfirmDone, PillScanningEvent.ConfirmDone)
        assertSame(PillScanningEvent.CancelDone, PillScanningEvent.CancelDone)
        assertSame(PillScanningEvent.NoteSkip, PillScanningEvent.NoteSkip)
    }

    @Test
    fun finalDone_fullCoverage() {
        val e = PillScanningEvent.FinalDone(StepState.SCAN, 12)
        assertEquals(StepState.SCAN, e.stepType)
        assertEquals(12, e.totalCount)
        assertEquals(PillScanningEvent.FinalDone(StepState.SCAN, 12), e)
        assertEquals(PillScanningEvent.FinalDone(StepState.SCAN, 12).hashCode(), e.hashCode())
        assertNotEquals(e, e.copy(totalCount = 0))
        assertTrue(e.toString().contains("12"))
        assertEquals(0, e.copy(totalCount = 0).totalCount)
        assertEquals(StepState.SCAN, e.component1())
        assertEquals(12, e.component2())
    }

    @Test
    fun noteSaved_fullCoverage() {
        val e = PillScanningEvent.NoteSaved("hi")
        assertEquals("hi", e.note)
        assertEquals(PillScanningEvent.NoteSaved("hi"), e)
        assertEquals(PillScanningEvent.NoteSaved("hi").hashCode(), e.hashCode())
        assertNotEquals(e, e.copy(note = "bye"))
        assertTrue(e.toString().contains("hi"))
        assertEquals("bye", e.copy(note = "bye").note)
        assertEquals("hi", e.component1())
    }

    @Test
    fun addVialPhotoInTxn_fullCoverage() {
        // Bitmap is an Android type -> relaxed mock.
        val bmp = mockk<Bitmap>(relaxed = true)
        val e = PillScanningEvent.AddVialPhotoInTxn(7, bmp)
        assertEquals(7, e.filteredCount)
        assertSame(bmp, e.bitmap)
        assertTrue(e is PillScanningEvent)
        // equals/hashCode use identity for the mock bitmap
        assertEquals(PillScanningEvent.AddVialPhotoInTxn(7, bmp), e)
        assertEquals(PillScanningEvent.AddVialPhotoInTxn(7, bmp).hashCode(), e.hashCode())
        assertNotEquals(e, e.copy(filteredCount = 8))
        assertTrue(e.toString().contains("7"))
        assertEquals(8, e.copy(filteredCount = 8).filteredCount)
        assertEquals(7, e.component1())
        assertSame(bmp, e.component2())
    }
}
