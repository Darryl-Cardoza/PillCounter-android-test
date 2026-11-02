package com.dispensesure.retail.feature.menu.presentation.viewmodel

import android.util.Log
import app.cash.turbine.test
import com.dispensesure.retail.core.faceAuth.logic.SessionLockController
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MenuViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var dispenseTxnDao: DispenseTxnDao
    private lateinit var stockBatchDao: StockBatchDao
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var sessionLockController: SessionLockController

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // AppLogger wraps android.util.Log, which is not available on the JVM.
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        dispenseTxnDao = mockk(relaxed = true)
        stockBatchDao = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        sessionLockController = mockk(relaxed = true)

        // Sensible defaults so the init observer does not blow up.
        every { dispenseTxnDao.getTotalCompletedTransactionCount(any(), any()) } returns flowOf(0)
        every { stockBatchDao.getUnsyncedCompletedBatchCount(any()) } returns flowOf(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): MenuViewModel =
        MenuViewModel(dispenseTxnDao, stockBatchDao, preferenceHelper, sessionLockController)

    @Test
    fun `initial uiState has the unsynced count at zero`() {
        assertEquals(0, createViewModel().uiState.value.unsyncedTransactionCount)
    }

    // ────────────────────────────── unsynced count ──────────────────────────────

    @Test
    fun `init adds unsynced dispenses and batches`() = runTest(testDispatcher) {
        every { dispenseTxnDao.getTotalCompletedTransactionCount(any(), any()) } returns flowOf(6)
        every { stockBatchDao.getUnsyncedCompletedBatchCount(any()) } returns flowOf(9)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            // combine: 6 dispense + 9 batch = 15
            assertEquals(15, awaitItem().unsyncedTransactionCount)
        }
    }

    @Test
    fun `observeUnsyncedTransactionCount catch keeps default count`() = runTest(testDispatcher) {
        every { dispenseTxnDao.getTotalCompletedTransactionCount(any(), any()) } returns
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

    // ────────────────────────────── lock screen ──────────────────────────────

    @Test
    fun `hasEnabledFaceProfile mirrors sessionLockController`() = runTest(testDispatcher) {
        every { sessionLockController.hasEnabledProfile } returns MutableStateFlow(true)

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.hasEnabledFaceProfile.value)
    }

    @Test
    fun `lockSessionNow delegates to sessionLockController and returns true when locked`() = runTest(testDispatcher) {
        every { sessionLockController.lockNow() } returns true

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.lockSessionNow())
        verify(exactly = 1) { sessionLockController.lockNow() }
    }

    @Test
    fun `lockSessionNow returns false when no face profile is enabled`() = runTest(testDispatcher) {
        every { sessionLockController.lockNow() } returns false

        val vm = createViewModel()
        advanceUntilIdle()

        assertFalse(vm.lockSessionNow())
    }
}
