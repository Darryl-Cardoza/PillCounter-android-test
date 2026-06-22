package com.rite.pillcounting.feature.batchCount.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDrugGroupTest {

    private fun lot(
        lotNo: String? = "LOT1",
        expiry: String? = "2030-01",
        count: Int = 5
    ) = BatchLotEntry(lotNo, expiry, count)

    private fun group(
        drugId: Long? = 10L,
        drugName: String = "Aspirin",
        ndc: String = "1234",
        sealedTotal: Int = 10,
        openedTotal: Int = 4,
        totalCount: Int = 14,
        sealedBottleQty: Int = 2,
        sealedLots: List<BatchLotEntry> = listOf(lot()),
        openedLots: List<BatchLotEntry> = emptyList()
    ) = BatchDrugGroup(
        drugId, drugName, ndc, sealedTotal, openedTotal,
        totalCount, sealedBottleQty, sealedLots, openedLots
    )

    // ────────────────────────────── BatchLotEntry ──────────────────────────────

    @Test
    fun `BatchLotEntry exposes its properties`() {
        val entry = lot(lotNo = "L99", expiry = "2031-12", count = 7)
        assertEquals("L99", entry.lotNo)
        assertEquals("2031-12", entry.expiry)
        assertEquals(7, entry.count)
    }

    @Test
    fun `BatchLotEntry supports null lot and expiry`() {
        val entry = BatchLotEntry(lotNo = null, expiry = null, count = 0)
        assertEquals(null, entry.lotNo)
        assertEquals(null, entry.expiry)
        assertEquals(0, entry.count)
    }

    @Test
    fun `BatchLotEntry equals hashCode and toString`() {
        val a = lot()
        val b = lot()
        val c = lot(count = 99)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertTrue(a.toString().contains("LOT1"))
    }

    @Test
    fun `BatchLotEntry copy and componentN`() {
        val original = lot()
        val copy = original.copy(count = 42)

        assertEquals("LOT1", copy.component1())
        assertEquals("2030-01", copy.component2())
        assertEquals(42, copy.component3())
        assertEquals(original.lotNo, copy.lotNo)
    }

    // ────────────────────────────── BatchDrugGroup ──────────────────────────────

    @Test
    fun `BatchDrugGroup exposes its properties`() {
        val openedLots = listOf(lot(lotNo = "L2", count = 4))
        val g = group(openedLots = openedLots)

        assertEquals(10L, g.drugId)
        assertEquals("Aspirin", g.drugName)
        assertEquals("1234", g.ndc)
        assertEquals(10, g.sealedTotal)
        assertEquals(4, g.openedTotal)
        assertEquals(14, g.totalCount)
        assertEquals(2, g.sealedBottleQty)
        assertEquals(1, g.sealedLots.size)
        assertEquals(openedLots, g.openedLots)
    }

    @Test
    fun `BatchDrugGroup supports null drugId`() {
        val g = group(drugId = null)
        assertEquals(null, g.drugId)
    }

    @Test
    fun `BatchDrugGroup equals hashCode and toString`() {
        val a = group()
        val b = group()
        val c = group(drugName = "Tylenol")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertTrue(a.toString().contains("Aspirin"))
    }

    @Test
    fun `BatchDrugGroup copy and componentN`() {
        val original = group()
        val copy = original.copy(drugName = "Ibuprofen", totalCount = 20)

        assertEquals(10L, copy.component1())
        assertEquals("Ibuprofen", copy.component2())
        assertEquals("1234", copy.component3())
        assertEquals(10, copy.component4())
        assertEquals(4, copy.component5())
        assertEquals(20, copy.component6())
        assertEquals(2, copy.component7())
        assertEquals(original.sealedLots, copy.component8())
        assertEquals(original.openedLots, copy.component9())
    }
}
