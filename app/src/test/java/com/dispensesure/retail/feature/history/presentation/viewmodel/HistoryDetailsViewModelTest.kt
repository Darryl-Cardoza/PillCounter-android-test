package com.dispensesure.retail.feature.history.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.models.dtos.DispenseCountInfo
import com.dispensesure.retail.core.room.models.dtos.DispenseTxnDetail
import com.dispensesure.retail.navigation.Screen
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class HistoryDetailsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var dispenseTxnDao: DispenseTxnDao

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

        // Sensible defaults so the init block does not blow up.
        coEvery { dispenseTxnDao.getDispenseTxnDetail(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(txnId: Long = 1L): HistoryDetailsViewModel =
        HistoryDetailsViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Screen.HistoryDetail.ARG_TXN_ID to txnId)),
            dispenseTxnDao = dispenseTxnDao,
        )

    private fun detail(
        txnId: Long = 1L,
        pillCount: Int = 5,
    ) = DispenseCountInfo(
        txnId = txnId,
        pillCount = pillCount,
        imagePath = null,
        step = null,
    )

    private fun txnWithDetails(
        txnId: Long = 1L,
        details: List<DispenseCountInfo> = emptyList()
    ) = DispenseTxnDetail(
        txnId = txnId,
        drugName = "Aspirin",
        drugId = 10L,
        ndc = "1234",
        targetCount = 100,
        note = null,
        createdAt = 0L,
        bottlesJson = null,
        totalPillCount = 50,
        drugType = null,
        counts = details,
        isComingFromHL7 = false
    )

    // ────────────────────────────── init / getTransactionDetails ──────────────────────────────

    @Test
    fun `init loads txn with its details`() = runTest(testDispatcher) {
        val txn = txnWithDetails(
            txnId = 1L,
            details = listOf(
                detail(txnId = 1L),
                detail(txnId = 2L),
                detail(txnId = 3L)
            )
        )
        coEvery { dispenseTxnDao.getDispenseTxnDetail(1L) } returns txn

        val vm = createViewModel(txnId = 1L)
        advanceUntilIdle()

        vm.uiState.test {
            val state = awaitItem()
            val info = state.txnInfo!!
            assertEquals(1L, info.txnId)
            assertEquals(3, info.counts.size)
            assertEquals(listOf(1L, 2L, 3L), info.counts.map { it.txnId })
        }
        coVerify(exactly = 1) { dispenseTxnDao.getDispenseTxnDetail(1L) }
    }

    @Test
    fun `init sets null txnInfo when no transaction found`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.getDispenseTxnDetail(99L) } returns null

        val vm = createViewModel(txnId = 99L)
        advanceUntilIdle()

        vm.uiState.test {
            assertNull(awaitItem().txnInfo)
        }
    }

    // ────────────────────────────── deleteTransaction ──────────────────────────────

    @Test
    fun `deleteTransaction calls softDelete with current txn id`() = runTest(testDispatcher) {
        val vm = createViewModel(txnId = 7L)
        advanceUntilIdle()

        vm.deleteTransaction()
        advanceUntilIdle()

        coVerify(exactly = 1) { dispenseTxnDao.softDelete(eq(7L), any()) }
    }

    @Test
    fun `deleteTransaction swallows exception from dao`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.softDelete(eq(7L), any()) } throws RuntimeException("db error")

        val vm = createViewModel(txnId = 7L)
        advanceUntilIdle()

        // Should not throw.
        vm.deleteTransaction()
        advanceUntilIdle()

        coVerify(exactly = 1) { dispenseTxnDao.softDelete(eq(7L), any()) }
    }

}
