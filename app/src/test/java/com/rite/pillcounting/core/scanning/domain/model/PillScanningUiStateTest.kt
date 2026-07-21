package com.rite.pillcounting.core.scanning.domain.model

import com.rite.pillcounting.core.scanning.logic.GloveDetection
import com.rite.pillcounting.core.scanning.logic.TrayColor
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PillScanningUiStateTest {

    private val pill = DetectedPill(1f, 2f, 0.5f)

    // GloveDetection wraps android.graphics.RectF -> use relaxed mock for rect.
    private fun glove() = GloveDetection(
        rect = mockk(relaxed = true),
        confidence = 0.8f,
        classId = 0,
        className = "gloves"
    )

    private fun full() = PillScanningUiState(
        scanType = "FIXED",
        drugName = "Crocin",
        totalCount = 100,
        targetCount = 120,
        currentScanCount = 20,
        txnDetailHistory = listOf(mockk(relaxed = true)),
        detectedPills = listOf(pill),
        filteredPills = listOf(pill),
        gloveDetections = listOf(glove()),
        isPaused = true,
        isLoading = true,
        restrictAdd = true,
        showConfirmDialog = true,
        showNoTransaction = true,
        showTargetCountDialog = true,
        showIdleOverlay = true,
        showNotesDialog = true,
        imageFrameWidth = 640,
        imageFrameHeight = 480,
        isAddCooldown = true,
        addCount = 3,
        showDialogForControl = true,
        showErrorMessage = "err",
        isHl7Txn = true,
        showCountMismatchDialog = true,
        showEndStockCountDialog = true,
        stockCountSessionTotal = 50,
        pendingTrayColorForClassification = TrayColor.WHITE
    )

    @Test
    fun defaults_areCorrect() {
        val s = PillScanningUiState()
        assertEquals("REGULAR", s.scanType)
        assertEquals("", s.drugName)
        assertEquals(0, s.totalCount)
        assertEquals(0, s.targetCount)
        assertEquals(0, s.currentScanCount)
        assertTrue(s.txnDetailHistory.isEmpty())
        assertTrue(s.detectedPills.isEmpty())
        assertTrue(s.filteredPills.isEmpty())
        assertTrue(s.gloveDetections.isEmpty())
        assertFalse(s.isPaused)
        assertFalse(s.isLoading)
        assertFalse(s.restrictAdd)
        assertFalse(s.showConfirmDialog)
        assertFalse(s.showNoTransaction)
        assertFalse(s.showTargetCountDialog)
        assertFalse(s.showIdleOverlay)
        assertFalse(s.showNotesDialog)
        assertEquals(0, s.imageFrameWidth)
        assertEquals(0, s.imageFrameHeight)
        assertFalse(s.isAddCooldown)
        assertEquals(0, s.addCount)
        assertFalse(s.showDialogForControl)
        assertNull(s.showErrorMessage)
        assertFalse(s.isHl7Txn)
        assertFalse(s.showCountMismatchDialog)
        assertFalse(s.showEndStockCountDialog)
        assertEquals(0, s.stockCountSessionTotal)
        assertNull(s.pendingTrayColorForClassification)
    }

    @Test
    fun getters_returnValues() {
        val s = full()
        assertEquals("FIXED", s.scanType)
        assertEquals("Crocin", s.drugName)
        assertEquals(100, s.totalCount)
        assertEquals(120, s.targetCount)
        assertEquals(20, s.currentScanCount)
        assertEquals(1, s.txnDetailHistory.size)
        assertEquals(listOf(pill), s.detectedPills)
        assertEquals(listOf(pill), s.filteredPills)
        assertEquals(1, s.gloveDetections.size)
        assertTrue(s.isPaused)
        assertTrue(s.isLoading)
        assertTrue(s.restrictAdd)
        assertTrue(s.showConfirmDialog)
        assertTrue(s.showNoTransaction)
        assertTrue(s.showTargetCountDialog)
        assertTrue(s.showIdleOverlay)
        assertTrue(s.showNotesDialog)
        assertEquals(640, s.imageFrameWidth)
        assertEquals(480, s.imageFrameHeight)
        assertTrue(s.isAddCooldown)
        assertEquals(3, s.addCount)
        assertTrue(s.showDialogForControl)
        assertEquals("err", s.showErrorMessage)
        assertTrue(s.isHl7Txn)
        assertTrue(s.showCountMismatchDialog)
        assertTrue(s.showEndStockCountDialog)
        assertEquals(50, s.stockCountSessionTotal)
        assertEquals(TrayColor.WHITE, s.pendingTrayColorForClassification)
    }

    @Test
    fun equalsHashCode_equal() {
        val a = PillScanningUiState(drugName = "A", detectedPills = listOf(pill))
        val b = PillScanningUiState(drugName = "A", detectedPills = listOf(pill))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_notEqual() {
        val a = PillScanningUiState(drugName = "A")
        assertNotEquals(a, a.copy(drugName = "B"))
    }

    @Test
    fun toString_containsField() {
        assertTrue(PillScanningUiState(drugName = "Crocin").toString().contains("Crocin"))
    }

    @Test
    fun copy_overrides() {
        assertEquals("Z", PillScanningUiState().copy(drugName = "Z").drugName)
    }

    @Test
    fun componentN_returnValues() {
        val s = full()
        assertEquals("FIXED", s.component1())           // scanType
        assertEquals("Crocin", s.component2())          // drugName
        assertEquals("", s.component3())                // ndc
        assertEquals("", s.component4())                // strength
        assertEquals("", s.component5())                // dosageForm
        assertEquals("", s.component6())                // bucket
        assertEquals("", s.component7())                // drugImage
        assertEquals(100, s.component8())               // totalCount
        assertEquals(120, s.component9())               // targetCount
        assertEquals(20, s.component10())               // currentScanCount
        assertEquals(s.txnDetailHistory, s.component11()) // txnDetailHistory
        assertEquals(listOf(pill), s.component12())     // detectedPills
        assertEquals(listOf(pill), s.component13())     // filteredPills
        assertEquals(s.gloveDetections, s.component14()) // gloveDetections
        assertTrue(s.component15())                     // isPaused
        assertTrue(s.component16())                     // isLoading
        assertTrue(s.component17())                     // restrictAdd
        assertTrue(s.component18())                     // showConfirmDialog
        assertTrue(s.component19())                     // showNoTransaction
        assertTrue(s.component20())                     // showTargetCountDialog
        assertTrue(s.component21())                     // showIdleOverlay
        assertTrue(s.component22())                     // showNotesDialog
        assertEquals(640, s.component23())              // imageFrameWidth
        assertEquals(480, s.component24())              // imageFrameHeight
        assertTrue(s.component25())                     // isAddCooldown
        assertEquals(3, s.component26())                // addCount
        assertTrue(s.component27())                     // showDialogForControl
        assertEquals("err", s.component28())            // showErrorMessage
        assertTrue(s.component29())                     // isHl7Txn
        assertTrue(s.component30())                     // showCountMismatchDialog
        assertTrue(s.component31())                     // showEndStockCountDialog
        assertEquals(50, s.component32())               // stockCountSessionTotal
        assertEquals(TrayColor.WHITE, s.component33())  // pendingTrayColorForClassification
    }
}
