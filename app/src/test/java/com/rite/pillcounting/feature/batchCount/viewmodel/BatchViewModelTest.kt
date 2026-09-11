package com.rite.pillcounting.feature.batchCount.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.batchCount.presentation.viewmodel.BatchViewModel
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [BatchViewModel].
 *
 * init with argBatchId=0 calls batchDao.getLatest().
 * init with a non-zero argBatchId calls batchDao.getById and populates batchEntity.
 * deleteBatch and getCurrentUser are exercised via coVerify and state assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val savedStateHandle: SavedStateHandle = mockk(relaxed = true)
    private val stockTxnDao: StockTxnDao = mockk(relaxed = true)
    private val bottleInfoDao: BottleInfoDao = mockk(relaxed = true)
    private val batchDao: BatchDao = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)

    private lateinit var viewModel: BatchViewModel

    @Before
    fun setup() {
        every { savedStateHandle.get<Long>("batch_id") } returns 0L
        viewModel = BatchViewModel(
            savedStateHandle = savedStateHandle,
            stockTxnDao = stockTxnDao,
            bottleInfoDao = bottleInfoDao,
            batchDao = batchDao,
            preferenceHelper = preferenceHelper,
        )
    }

    @After
    fun tearDown() { unmockkAll() }

    // BATCH_VM_001
    @Test
    fun `init with argBatchId zero calls batchDao getLatest to resolve the batch`() = runTest {
        advanceUntilIdle()

        coVerify(atLeast = 1) { batchDao.getLatest() }
    }

    // BATCH_VM_002
    @Test
    fun `init with non-zero argBatchId loads the entity into batchEntity`() = runTest {
        every { savedStateHandle.get<Long>("batch_id") } returns 5L
        val entity = BatchEntity(
            batchId = 5L, startDateTime = 0L, endDateTime = null,
            status = BatchStatus.COMPLETED, isDeleted = false, note = null, bucketId = null,
        )
        coEvery { batchDao.getById(5L) } returns entity

        val vm = BatchViewModel(
            savedStateHandle = savedStateHandle,
            stockTxnDao = stockTxnDao,
            bottleInfoDao = bottleInfoDao,
            batchDao = batchDao,
            preferenceHelper = preferenceHelper,
        )
        advanceUntilIdle()

        assertEquals(entity, vm.batchEntity.value)
    }

    // BATCH_VM_004
    @Test
    fun `deleteBatch calls softDelete and deleteByBatchIds then invokes the callback`() = runTest {
        every { savedStateHandle.get<Long>("batch_id") } returns 3L
        val vm = BatchViewModel(
            savedStateHandle = savedStateHandle,
            stockTxnDao = stockTxnDao,
            bottleInfoDao = bottleInfoDao,
            batchDao = batchDao,
            preferenceHelper = preferenceHelper,
        )
        advanceUntilIdle()

        var callbackFired = false
        vm.deleteBatch { callbackFired = true }
        advanceUntilIdle()

        coVerify { batchDao.softDelete(3L) }
        coVerify { stockTxnDao.deleteByBatchIds(listOf(3L)) }
        assertTrue(callbackFired)
    }

    // BATCH_VM_005
    @Test
    fun `getCurrentUser returns the logged-in email when it is set`() {
        every { preferenceHelper.getLoggedInEmail() } returns "alice@rx.com"

        val result = viewModel.getCurrentUser()

        assertEquals("alice@rx.com", result)
    }

    // BATCH_VM_006
    @Test
    fun `getCurrentUser falls back to getUserId when no logged-in email`() {
        every { preferenceHelper.getLoggedInEmail() } returns null
        every { preferenceHelper.getUserId() } returns "fallback_user"

        val result = viewModel.getCurrentUser()

        assertEquals("fallback_user", result)
    }
}
