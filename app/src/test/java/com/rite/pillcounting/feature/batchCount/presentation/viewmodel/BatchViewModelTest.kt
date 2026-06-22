package com.rite.pillcounting.feature.batchCount.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.batchCount.domain.model.BatchDrugGroup
import com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var pillCountTxnDao: PillCountTxnDao
    private lateinit var batchDao: BatchDao
    private lateinit var hl7Repository: Hl7Repository
    private lateinit var preferenceHelper: PreferenceHelper

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        pillCountTxnDao = mockk(relaxed = true)
        batchDao = mockk(relaxed = true)
        hl7Repository = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)

        // Sensible defaults so the init block does not blow up.
        every { preferenceHelper.getLocalId() } returns 1L
        coEvery { batchDao.getLatest() } returns null
        coEvery { batchDao.getById(any()) } returns null
        coEvery { pillCountTxnDao.getUniqueNdcCountForBatch(any(), any()) } returns 0
        every { pillCountTxnDao.observeByBatchId(any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(batchId: Long? = null): BatchViewModel {
        val savedStateHandle = SavedStateHandle(
            if (batchId == null) emptyMap() else mapOf("batch_id" to batchId)
        )
        return BatchViewModel(savedStateHandle, pillCountTxnDao, batchDao, hl7Repository, preferenceHelper)
    }

    private fun txn(
        txnId: Long = 1L,
        drugId: Long? = 10L,
        drugName: String? = "Aspirin",
        ndc: String? = "1234",
        lotNo: String? = "LOT1",
        expiry: String? = "2030-01",
        bottleQty: Int? = 0,
        looseQty: Int? = 0,
        packageQty: Int? = 1
    ) = BatchTxnDto(txnId, drugId, drugName, ndc, lotNo, expiry, bottleQty, looseQty, packageQty)

    // ────────────────────────────── init block ──────────────────────────────

    @Test
    fun `init with no batch_id arg and no latest batch resolves to 0`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = null)
        advanceUntilIdle()

        assertEquals(0L, vm.displayBatchId.value)
        assertEquals(false, vm.isBatchCompleted.value)
        assertNull(vm.batchEntity.value)
        assertEquals(0, vm.uniqueNdcCount.value)
        coVerify(exactly = 0) { batchDao.getById(any()) }
    }

    @Test
    fun `init with zero batch_id arg falls back to latest batch`() = runTest(testDispatcher) {
        val entity = BatchEntity(batchId = 7L, status = BatchStatus.INPROGRESS)
        coEvery { batchDao.getLatest() } returns entity
        coEvery { batchDao.getById(7L) } returns entity
        coEvery { pillCountTxnDao.getUniqueNdcCountForBatch(7L, 1L) } returns 3

        val vm = createViewModel(batchId = 0L)
        advanceUntilIdle()

        assertEquals(7L, vm.displayBatchId.value)
        assertEquals(false, vm.isBatchCompleted.value)
        assertEquals(entity, vm.batchEntity.value)
        assertEquals(3, vm.uniqueNdcCount.value)
    }

    @Test
    fun `init with explicit batch_id loads completed entity`() = runTest(testDispatcher) {
        val entity = BatchEntity(batchId = 9L, status = BatchStatus.COMPLETED)
        coEvery { batchDao.getById(9L) } returns entity
        coEvery { pillCountTxnDao.getUniqueNdcCountForBatch(9L, 1L) } returns 5

        val vm = createViewModel(batchId = 9L)
        advanceUntilIdle()

        assertEquals(9L, vm.displayBatchId.value)
        assertTrue(vm.isBatchCompleted.value)
        assertEquals(entity, vm.batchEntity.value)
        assertEquals(5, vm.uniqueNdcCount.value)
        coVerify(exactly = 0) { batchDao.getLatest() }
    }

    @Test
    fun `init with explicit batch_id but missing entity leaves completed false`() = runTest(testDispatcher) {
        coEvery { batchDao.getById(9L) } returns null

        val vm = createViewModel(batchId = 9L)
        advanceUntilIdle()

        assertEquals(9L, vm.displayBatchId.value)
        assertEquals(false, vm.isBatchCompleted.value)
        assertNull(vm.batchEntity.value)
    }

    // ────────────────────────────── drugGroups ──────────────────────────────

    @Test
    fun `drugGroups emits empty list when resolved id is zero`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 0L)
        advanceUntilIdle()

        vm.drugGroups.test {
            assertEquals(emptyList<Any>(), awaitItem())
        }
    }

    @Test
    fun `drugGroups groups and maps transactions for a valid batch`() = runTest(testDispatcher) {
        val txns = listOf(
            // Sealed lot: bottleQty 2 * packageQty 5 = 10
            txn(txnId = 1, drugId = 10L, bottleQty = 2, looseQty = 0, packageQty = 5),
            // Opened lot: looseQty 4
            txn(txnId = 2, drugId = 10L, lotNo = "LOT2", bottleQty = 0, looseQty = 4, packageQty = 1),
            // Different drug, nulls -> defaults exercised
            txn(
                txnId = 3, drugId = 20L, drugName = null, ndc = null,
                bottleQty = null, looseQty = null, packageQty = null
            )
        )
        every { pillCountTxnDao.observeByBatchId(5L) } returns flowOf(txns)

        val vm = createViewModel(batchId = 5L)
        advanceUntilIdle()

        vm.drugGroups.test {
            // WhileSubscribed replays the initial empty value before the mapped list arrives.
            var groups = awaitItem()
            if (groups.isEmpty()) groups = awaitItem()
            assertEquals(2, groups.size)

            val first = groups.first { it.drugId == 10L }
            assertEquals("Aspirin", first.drugName)
            assertEquals("1234", first.ndc)
            assertEquals(10, first.sealedTotal)
            assertEquals(4, first.openedTotal)
            assertEquals(14, first.totalCount)
            assertEquals(2, first.sealedBottleQty)
            assertEquals(1, first.sealedLots.size)
            assertEquals(1, first.openedLots.size)

            val second = groups.first { it.drugId == 20L }
            assertEquals("Unknown Drug", second.drugName)
            assertEquals("", second.ndc)
            assertEquals(0, second.sealedTotal)
            assertEquals(0, second.openedTotal)
            assertEquals(0, second.totalCount)
            assertTrue(second.sealedLots.isEmpty())
            assertTrue(second.openedLots.isEmpty())
        }
    }

    @Test
    fun `drugGroups runs groupAndMap via active collector`() = runTest(testDispatcher) {
        val txns = listOf(
            txn(txnId = 1, drugId = 10L, bottleQty = 3, looseQty = 0, packageQty = 2),
            txn(txnId = 2, drugId = 10L, bottleQty = 0, looseQty = 6, packageQty = 1)
        )
        every { pillCountTxnDao.observeByBatchId(8L) } returns flowOf(txns)

        val vm = createViewModel(batchId = 8L)
        // Start a real collector so WhileSubscribed activates the upstream and groupAndMap runs.
        val job = launch { vm.drugGroups.collect {} }
        advanceUntilIdle()

        val groups = vm.drugGroups.value
        assertEquals(1, groups.size)
        val g = groups.first()
        assertEquals(6, g.sealedTotal)   // 3 bottles * packageQty 2
        assertEquals(6, g.openedTotal)   // 6 loose
        assertEquals(12, g.totalCount)
        assertEquals(3, g.sealedBottleQty)
        job.cancel()
    }

    @Suppress("UNCHECKED_CAST")
    private fun BatchViewModel.invokeGroupAndMap(txns: List<BatchTxnDto>): List<BatchDrugGroup> {
        val method = BatchViewModel::class.java
            .getDeclaredMethod("groupAndMap", List::class.java)
            .apply { isAccessible = true }
        return method.invoke(this, txns) as List<BatchDrugGroup>
    }

    @Test
    fun `groupAndMap groups by drug and computes sealed and opened totals`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 1L)
        advanceUntilIdle()

        val txns = listOf(
            // Drug 10: one sealed (3 * 2 = 6) and one opened (looseQty 6) lot.
            txn(txnId = 1, drugId = 10L, lotNo = "L1", bottleQty = 3, looseQty = 0, packageQty = 2),
            txn(txnId = 2, drugId = 10L, lotNo = "L2", bottleQty = 0, looseQty = 6, packageQty = 1),
            // Drug 20: nulls exercise the default branches (packageQty -> 1, qtys -> 0,
            // drugName -> "Unknown Drug", ndc -> "").
            txn(
                txnId = 3, drugId = 20L, drugName = null, ndc = null,
                bottleQty = null, looseQty = null, packageQty = null
            )
        )

        val groups = vm.invokeGroupAndMap(txns)

        assertEquals(2, groups.size)

        val drug10 = groups.first { it.drugId == 10L }
        assertEquals("Aspirin", drug10.drugName)
        assertEquals("1234", drug10.ndc)
        assertEquals(6, drug10.sealedTotal)
        assertEquals(6, drug10.openedTotal)
        assertEquals(12, drug10.totalCount)
        assertEquals(3, drug10.sealedBottleQty)
        assertEquals(1, drug10.sealedLots.size)
        assertEquals(1, drug10.openedLots.size)

        val drug20 = groups.first { it.drugId == 20L }
        assertEquals("Unknown Drug", drug20.drugName)
        assertEquals("", drug20.ndc)
        assertEquals(0, drug20.sealedTotal)
        assertEquals(0, drug20.openedTotal)
        assertEquals(0, drug20.totalCount)
        assertEquals(0, drug20.sealedBottleQty)
        assertTrue(drug20.sealedLots.isEmpty())
        assertTrue(drug20.openedLots.isEmpty())
    }

    @Test
    fun `groupAndMap returns empty list for no transactions`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 1L)
        advanceUntilIdle()

        assertTrue(vm.invokeGroupAndMap(emptyList()).isEmpty())
    }

    // ────────────────────────────── deleteBatch ──────────────────────────────

    @Test
    fun `deleteBatch soft-deletes batch and txns then calls onDone`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 4L)
        advanceUntilIdle()

        var done = false
        vm.deleteBatch { done = true }
        advanceUntilIdle()

        assertTrue(done)
        coVerify(exactly = 1) { batchDao.softDelete(4L) }
        coVerify(exactly = 1) { pillCountTxnDao.deleteTransactionsByBatchIds(listOf(4L)) }
    }

    @Test
    fun `deleteBatch with zero id skips dao calls but still calls onDone`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 0L)
        advanceUntilIdle()

        var done = false
        vm.deleteBatch { done = true }
        advanceUntilIdle()

        assertTrue(done)
        coVerify(exactly = 0) { batchDao.softDelete(any()) }
        coVerify(exactly = 0) { pillCountTxnDao.deleteTransactionsByBatchIds(any()) }
    }

    @Test
    fun `deleteBatch still calls onDone when dao throws`() = runTest(testDispatcher) {
        coEvery { batchDao.softDelete(4L) } throws RuntimeException("db error")

        val vm = createViewModel(batchId = 4L)
        advanceUntilIdle()

        var done = false
        vm.deleteBatch { done = true }
        advanceUntilIdle()

        assertTrue(done)
    }

    // ────────────────────────────── getCurrentUser ──────────────────────────────

    @Test
    fun `getCurrentUser returns first recent login when available`() = runTest(testDispatcher) {
        every { preferenceHelper.getRecentLogins() } returns listOf("alice", "bob")

        val vm = createViewModel(batchId = 1L)
        advanceUntilIdle()

        assertEquals("alice", vm.getCurrentUser())
    }

    @Test
    fun `getCurrentUser falls back to userId when no recent logins`() = runTest(testDispatcher) {
        every { preferenceHelper.getRecentLogins() } returns emptyList()
        every { preferenceHelper.getUserId() } returns "charlie"

        val vm = createViewModel(batchId = 1L)
        advanceUntilIdle()

        assertEquals("charlie", vm.getCurrentUser())
    }

    @Test
    fun `getCurrentUser returns dash when nothing available`() = runTest(testDispatcher) {
        every { preferenceHelper.getRecentLogins() } returns emptyList()
        every { preferenceHelper.getUserId() } returns null

        val vm = createViewModel(batchId = 1L)
        advanceUntilIdle()

        assertEquals("—", vm.getCurrentUser())
    }

    // ────────────────────────────── endBatch ──────────────────────────────

    @Test
    fun `endBatch marks completed and updates note when note provided`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 6L)
        advanceUntilIdle()

        vm.endBatch("counted twice")
        advanceUntilIdle()

        coVerify(exactly = 1) { batchDao.markAsCompleted(6L, any()) }
        coVerify(exactly = 1) { batchDao.updateNote(6L, "counted twice") }
        coVerify(exactly = 1) { hl7Repository.buildAndSendInventoryResponse(6L) }
    }

    @Test
    fun `endBatch skips note update when note is null`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 6L)
        advanceUntilIdle()

        vm.endBatch(null)
        advanceUntilIdle()

        coVerify(exactly = 1) { batchDao.markAsCompleted(6L, any()) }
        coVerify(exactly = 0) { batchDao.updateNote(any(), any()) }
        coVerify(exactly = 1) { hl7Repository.buildAndSendInventoryResponse(6L) }
    }

    @Test
    fun `endBatch skips note update when note is blank`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 6L)
        advanceUntilIdle()

        vm.endBatch("   ")
        advanceUntilIdle()

        coVerify(exactly = 1) { batchDao.markAsCompleted(6L, any()) }
        coVerify(exactly = 0) { batchDao.updateNote(any(), any()) }
    }

    @Test
    fun `endBatch with zero id only sends hl7 response`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 0L)
        advanceUntilIdle()

        vm.endBatch("ignored")
        advanceUntilIdle()

        coVerify(exactly = 0) { batchDao.markAsCompleted(any()) }
        coVerify(exactly = 0) { batchDao.updateNote(any(), any()) }
        coVerify(exactly = 1) { hl7Repository.buildAndSendInventoryResponse(0L) }
    }
}
