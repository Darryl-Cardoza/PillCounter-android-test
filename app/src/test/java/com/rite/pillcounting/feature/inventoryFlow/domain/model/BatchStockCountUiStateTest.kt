package com.rite.pillcounting.feature.inventoryFlow.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchStockCountUiStateTest {

    // ─────────────────────────── ActiveNdc.totalPills ───────────────────────────

    @Test
    fun `totalPills computes pillsPerBottle times bottles plus openPills`() {
        val active = ActiveNdc(
            ndc = "12345",
            drugName = "Aspirin",
            bucket = "A1",
            batchNo = "B1",
            expiry = "2027-01-01",
            pillsPerBottle = 30,
            bottles = 2,
            openPills = 5,
        )
        assertEquals(65, active.totalPills)
    }

    @Test
    fun `totalPills defaults openPills to zero when not provided`() {
        val active = ActiveNdc(
            ndc = "12345",
            drugName = "Aspirin",
            bucket = "A1",
            batchNo = "B1",
            expiry = "2027-01-01",
            pillsPerBottle = 30,
            bottles = 2,
        )
        assertEquals(60, active.totalPills)
        assertEquals(0, active.openPills)
    }

    @Test
    fun `totalPills is zero when bottles and openPills are both zero`() {
        val active = ActiveNdc(
            ndc = "12345",
            drugName = "Aspirin",
            bucket = "A1",
            batchNo = "B1",
            expiry = "2027-01-01",
            pillsPerBottle = 30,
            bottles = 0,
            openPills = 0,
        )
        assertEquals(0, active.totalPills)
    }

    @Test
    fun `totalPills counts only openPills when bottles is zero`() {
        val active = ActiveNdc(
            ndc = "12345",
            drugName = "Aspirin",
            bucket = "A1",
            batchNo = "B1",
            expiry = "2027-01-01",
            pillsPerBottle = 30,
            bottles = 0,
            openPills = 7,
        )
        assertEquals(7, active.totalPills)
    }

    @Test
    fun `totalPills counts only bottle pills when openPills is zero`() {
        val active = ActiveNdc(
            ndc = "12345",
            drugName = "Aspirin",
            bucket = "A1",
            batchNo = "B1",
            expiry = "2027-01-01",
            pillsPerBottle = 10,
            bottles = 3,
            openPills = 0,
        )
        assertEquals(30, active.totalPills)
    }

    @Test
    fun `ActiveNdc default values for isHazardous serialNo and aggregated`() {
        val active = ActiveNdc(
            ndc = "12345",
            drugName = "Aspirin",
            bucket = "A1",
            batchNo = "B1",
            expiry = "2027-01-01",
            pillsPerBottle = 10,
            bottles = 1,
        )
        assertFalse(active.isHazardous)
        assertNull(active.serialNo)
        assertFalse(active.aggregated)
    }

    @Test
    fun `ActiveNdc allows overriding isHazardous serialNo and aggregated`() {
        val active = ActiveNdc(
            ndc = "12345",
            drugName = "Aspirin",
            bucket = "A1",
            batchNo = "B1",
            expiry = "2027-01-01",
            pillsPerBottle = 10,
            bottles = 1,
            isHazardous = true,
            serialNo = "SN123",
            aggregated = true,
        )
        assertTrue(active.isHazardous)
        assertEquals("SN123", active.serialNo)
        assertTrue(active.aggregated)
    }

    // ─────────────────────────── EditDrugDetails.sealedTotal / openTotal ───────────────────────────

    @Test
    fun `sealedTotal sums qty across sealedBottles rows`() {
        val details = EditDrugDetails(
            ndc = "12345",
            drugName = "Aspirin",
            bucket = "A1",
            sealedBottles = listOf(
                EditBatchRow(txnId = 1L, batchNo = "B1", expiry = "2027-01-01", qty = 10),
                EditBatchRow(txnId = 2L, batchNo = "B2", expiry = "2027-02-01", qty = 15),
            ),
            openPills = emptyList(),
        )
        assertEquals(25, details.sealedTotal)
    }

    @Test
    fun `openTotal sums qty across openPills rows`() {
        val details = EditDrugDetails(
            ndc = "12345",
            drugName = "Aspirin",
            bucket = "A1",
            sealedBottles = emptyList(),
            openPills = listOf(
                EditBatchRow(txnId = 3L, batchNo = "B3", expiry = "2027-03-01", qty = 4),
                EditBatchRow(txnId = 4L, batchNo = "B4", expiry = "2027-04-01", qty = 6),
            ),
        )
        assertEquals(10, details.openTotal)
    }

    @Test
    fun `sealedTotal and openTotal are zero for empty lists`() {
        val details = EditDrugDetails(
            ndc = "12345",
            drugName = "Aspirin",
            bucket = "A1",
            sealedBottles = emptyList(),
            openPills = emptyList(),
        )
        assertEquals(0, details.sealedTotal)
        assertEquals(0, details.openTotal)
    }

    @Test
    fun `sealedTotal and openTotal computed independently when both lists populated`() {
        val details = EditDrugDetails(
            ndc = "12345",
            drugName = "Aspirin",
            bucket = "A1",
            sealedBottles = listOf(EditBatchRow(txnId = 1L, batchNo = "B1", expiry = "2027-01-01", qty = 20)),
            openPills = listOf(EditBatchRow(txnId = 2L, batchNo = "B2", expiry = "2027-02-01", qty = 3)),
        )
        assertEquals(20, details.sealedTotal)
        assertEquals(3, details.openTotal)
    }

    // ─────────────────────────── data class equality / copy semantics ───────────────────────────

    @Test
    fun `BatchStockCountUiState equals compares by value`() {
        val a = BatchStockCountUiState(
            recentCounts = listOf(RecentBatchRow("111", "Drug A", 10, 1)),
            activeNdc = null,
            totalNdcs = 1,
            totalPills = 10,
        )
        val b = BatchStockCountUiState(
            recentCounts = listOf(RecentBatchRow("111", "Drug A", 10, 1)),
            activeNdc = null,
            totalNdcs = 1,
            totalPills = 10,
        )
        assertEquals(a, b)
    }

    @Test
    fun `BatchStockCountUiState with null activeNdc represents summary state`() {
        val state = BatchStockCountUiState(
            recentCounts = emptyList(),
            activeNdc = null,
            totalNdcs = 0,
            totalPills = 0,
        )
        assertNull(state.activeNdc)
        assertTrue(state.recentCounts.isEmpty())
    }

    @Test
    fun `RecentBatchRow copy allows overriding pills while preserving other fields`() {
        val row = RecentBatchRow(ndc = "111", drugName = "Drug A", pills = 10, bottles = 1)
        val updated = row.copy(pills = 20)
        assertEquals(20, updated.pills)
        assertEquals(row.ndc, updated.ndc)
        assertEquals(row.drugName, updated.drugName)
        assertEquals(row.bottles, updated.bottles)
    }
}
