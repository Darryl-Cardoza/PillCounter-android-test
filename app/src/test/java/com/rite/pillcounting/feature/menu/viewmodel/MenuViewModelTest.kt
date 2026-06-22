package com.rite.pillcounting.feature.menu.viewmodel

import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.menu.presentation.viewmodel.MenuViewModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [MenuViewModel].
 *
 * The init block launches 4 coroutines that collect from DAO flows. With relaxed
 * MockK the flows never emit, so state stays at its defaults — which is what these
 * tests verify for the simple accessor methods.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val pillCountTxnDao: PillCountTxnDao = mockk(relaxed = true)
    private val batchDao: BatchDao = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)

    private lateinit var viewModel: MenuViewModel

    @Before
    fun setup() {
        viewModel = MenuViewModel(
            pillCountTxnDao = pillCountTxnDao,
            batchDao = batchDao,
            preferenceHelper = preferenceHelper,
        )
    }

    @After
    fun tearDown() { unmockkAll() }

    // MENU_VM_001
    @Test
    fun `initial uiState has all counts at zero`() {
        val state = viewModel.uiState.value

        assertEquals(0, state.fixedCompleted)
        assertEquals(0, state.fixedPartial)
        assertEquals(0, state.regularPartial)
        assertEquals(0, state.regularCompleted)
        assertEquals(0, state.unsyncedTransactionCount)
    }

    // MENU_VM_002
    @Test
    fun `getSavedHistoryOption delegates to preferenceHelper getHistoryRetention`() {
        every { preferenceHelper.getHistoryRetention() } returns 30

        val result = viewModel.getSavedHistoryOption()

        assertEquals(30, result)
    }

    // MENU_VM_003
    @Test
    fun `getBucketList delegates to preferenceHelper getBucketList`() {
        every { preferenceHelper.getBucketList() } returns listOf("BucketA", "BucketB")

        val result = viewModel.getBucketList()

        assertEquals(listOf("BucketA", "BucketB"), result)
    }

    // MENU_VM_004
    @Test
    fun `getLastInProgressBatch returns the batch from batchDao getLatest`() = runTest {
        val entity = BatchEntity(
            batchId = 99L, startDateTime = 0L, endDateTime = null,
            status = BatchStatus.INPROGRESS, isDeleted = false, note = null, bucketId = null,
        )
        coEvery { batchDao.getLatest() } returns entity

        val result = viewModel.getLastInProgressBatch()

        assertEquals(entity, result)
    }

    // MENU_VM_005
    @Test
    fun `getLastInProgressBatch returns null when no batch exists`() = runTest {
        coEvery { batchDao.getLatest() } returns null

        val result = viewModel.getLastInProgressBatch()

        assertNull(result)
    }

    // MENU_VM_006
    @Test
    fun `createBatch calls batchDao insert and returns the new batch id`() = runTest {
        coEvery { batchDao.insert(any()) } returns 42L

        val result = viewModel.createBatch(bucketId = "bucket1")
        advanceUntilIdle()

        assertEquals(42L, result)
        coVerify { batchDao.insert(any()) }
    }

    // MENU_VM_007
    @Test
    fun `createBatch returns null when batchDao insert throws`() = runTest {
        coEvery { batchDao.insert(any()) } throws RuntimeException("DB error")

        val result = viewModel.createBatch(bucketId = "bucket1")

        assertNull(result)
    }
}
