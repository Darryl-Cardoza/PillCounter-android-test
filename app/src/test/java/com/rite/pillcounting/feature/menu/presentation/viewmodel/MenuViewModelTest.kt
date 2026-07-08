package com.rite.pillcounting.feature.menu.presentation.viewmodel

import android.util.Log
import app.cash.turbine.test
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.dtos.StatusTypeCount
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MenuViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var pillCountTxnDao: PillCountTxnDao
    private lateinit var batchDao: BatchDao
    private lateinit var preferenceHelper: PreferenceHelper

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // AppLogger wraps android.util.Log, which is not available on the JVM.
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        pillCountTxnDao = mockk(relaxed = true)
        batchDao = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)

        // Sensible defaults so the init observers do not blow up.
        every { preferenceHelper.getLocalId() } returns 1L
        every { pillCountTxnDao.observeDashboardCountsGrouped(any()) } returns flowOf(emptyList())
        every { pillCountTxnDao.getTotalCompletedTransactionCount(any(), any()) } returns flowOf(0)
        every { batchDao.observeActiveInProgressCount() } returns flowOf(0)
        every { batchDao.observeCompletedBatchCount() } returns flowOf(0)
        every { batchDao.getUnsyncedCompletedBatchCount(any()) } returns flowOf(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): MenuViewModel =
        MenuViewModel(pillCountTxnDao, batchDao, preferenceHelper)

    // ────────────────────────────── init observers (happy path) ──────────────────────────────

    @Test
    fun `init maps dashboard counts and batch counts into ui state`() = runTest(testDispatcher) {
        every { preferenceHelper.getLocalId() } returns 5L
        every { pillCountTxnDao.observeDashboardCountsGrouped(5L) } returns flowOf(
            listOf(
                StatusTypeCount(CountStatus.COMPLETED, true, 11),
                StatusTypeCount(CountStatus.PARTIAL, true, 7)
            )
        )
        every { batchDao.observeActiveInProgressCount() } returns flowOf(3)
        every { batchDao.observeCompletedBatchCount() } returns flowOf(4)
        every { pillCountTxnDao.getTotalCompletedTransactionCount(any(), any()) } returns flowOf(6)
        every { batchDao.getUnsyncedCompletedBatchCount(any()) } returns flowOf(9)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(11, state.fixedCompleted)
            assertEquals(7, state.fixedPartial)
            assertEquals(3, state.regularPartial)
            assertEquals(4, state.regularCompleted)
            // combine: 6 dispense + 9 batch = 15
            assertEquals(15, state.unsyncedTransactionCount)
        }
    }

    // ────────────────────────────── init observers (catch branches) ──────────────────────────────

    @Test
    fun `observeDashboardCounts catch keeps default state`() = runTest(testDispatcher) {
        every { pillCountTxnDao.observeDashboardCountsGrouped(any()) } returns
            flow { throw RuntimeException("dashboard error") }

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(0, state.fixedCompleted)
            assertEquals(0, state.fixedPartial)
        }
    }

    @Test
    fun `observeBatchCount catch keeps default regularPartial`() = runTest(testDispatcher) {
        every { batchDao.observeActiveInProgressCount() } returns
            flow { throw RuntimeException("batch error") }

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            assertEquals(0, awaitItem().regularPartial)
        }
    }

    @Test
    fun `observeCompletedBatchCount catch keeps default regularCompleted`() = runTest(testDispatcher) {
        every { batchDao.observeCompletedBatchCount() } returns
            flow { throw RuntimeException("completed error") }

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            assertEquals(0, awaitItem().regularCompleted)
        }
    }

    @Test
    fun `observeUnsyncedTransactionCount catch keeps default count`() = runTest(testDispatcher) {
        every { pillCountTxnDao.getTotalCompletedTransactionCount(any(), any()) } returns
            flow { throw RuntimeException("unsynced error") }

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            assertEquals(0, awaitItem().unsyncedTransactionCount)
        }
    }

    // ────────────────────────────── getSavedHistoryOption ──────────────────────────────

    @Test
    fun `getSavedHistoryOption returns history retention`() = runTest(testDispatcher) {
        every { preferenceHelper.getHistoryRetention() } returns 30

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(30, vm.getSavedHistoryOption())
    }

    // ────────────────────────────── getBucketList ──────────────────────────────

    @Test
    fun `getBucketList returns bucket list`() = runTest(testDispatcher) {
        every { preferenceHelper.getBucketList() } returns listOf("b1", "b2")

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("b1", "b2"), vm.getBucketList())
    }

    // ────────────────────────────── getLastInProgressBatch ──────────────────────────────

    @Test
    fun `getLastInProgressBatch returns latest batch`() = runTest(testDispatcher) {
        val entity = BatchEntity(batchId = 42L, status = BatchStatus.INPROGRESS)
        coEvery { batchDao.getLatest() } returns entity

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(entity, vm.getLastInProgressBatch())
    }

    @Test
    fun `getLastInProgressBatch returns null when no batch`() = runTest(testDispatcher) {
        coEvery { batchDao.getLatest() } returns null

        val vm = createViewModel()
        advanceUntilIdle()

        assertNull(vm.getLastInProgressBatch())
    }

    // ────────────────────────────── createBatch ──────────────────────────────

    @Test
    fun `createBatch inserts batch and returns id`() = runTest(testDispatcher) {
        coEvery { batchDao.insert(any()) } returns 123L

        val vm = createViewModel()
        advanceUntilIdle()

        val id = vm.createBatch("bucket-1")
        assertEquals(123L, id)
    }

    @Test
    fun `createBatch returns null when insert throws`() = runTest(testDispatcher) {
        coEvery { batchDao.insert(any()) } throws RuntimeException("insert error")

        val vm = createViewModel()
        advanceUntilIdle()

        assertNull(vm.createBatch("bucket-1"))
    }
}
