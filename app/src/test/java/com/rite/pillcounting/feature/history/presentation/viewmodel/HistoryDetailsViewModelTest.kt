package com.rite.pillcounting.feature.history.presentation.viewmodel

import android.util.Log
import app.cash.turbine.test
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.dtos.TxnDetailInfo
import com.rite.pillcounting.core.room.models.dtos.TxnWithDetails
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
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

    private lateinit var pillCountTxnDao: PillCountTxnDao
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
        preferenceHelper = mockk(relaxed = true)

        // Sensible defaults so the init block does not blow up.
        every { preferenceHelper.getTxnId() } returns 1L
        coEvery { pillCountTxnDao.getTxnWithDetails(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): HistoryDetailsViewModel =
        HistoryDetailsViewModel(preferenceHelper, pillCountTxnDao)

    private fun detail(
        txnId: Long = 1L,
        pillCount: Int = 5,
        isDeleted: Boolean = false
    ) = TxnDetailInfo(
        txnId = txnId,
        pillCount = pillCount,
        imagePath = null,
        type = null,
        isDeleted = isDeleted
    )

    private fun txnWithDetails(
        txnId: Long = 1L,
        details: List<TxnDetailInfo> = emptyList()
    ) = TxnWithDetails(
        txnId = txnId,
        drugName = "Aspirin",
        drugId = 10L,
        ndc = "1234",
        targetCount = 100,
        note = null,
        createdAt = 0L,
        barcodeImage = null,
        totalPillCount = 50,
        isDispense = true,
        drugType = null,
        txnDetails = details,
        isComingFromHL7 = false
    )

    // ────────────────────────────── init / getTransactionDetails ──────────────────────────────

    @Test
    fun `init loads txn and filters out soft-deleted details`() = runTest(testDispatcher) {
        val txn = txnWithDetails(
            txnId = 1L,
            details = listOf(
                detail(txnId = 1L, isDeleted = false),
                detail(txnId = 2L, isDeleted = true),
                detail(txnId = 3L, isDeleted = false)
            )
        )
        every { preferenceHelper.getTxnId() } returns 1L
        coEvery { pillCountTxnDao.getTxnWithDetails(1L) } returns txn

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            val state = awaitItem()
            val info = state.txnInfo!!
            assertEquals(1L, info.txnId)
            // Only the two non-deleted details survive.
            assertEquals(2, info.txnDetails.size)
            assertEquals(listOf(1L, 3L), info.txnDetails.map { it.txnId })
        }
        coVerify(exactly = 1) { pillCountTxnDao.getTxnWithDetails(1L) }
    }

    @Test
    fun `init sets null txnInfo when no transaction found`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 99L
        coEvery { pillCountTxnDao.getTxnWithDetails(99L) } returns null

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            assertNull(awaitItem().txnInfo)
        }
    }

    // ────────────────────────────── deleteTransaction ──────────────────────────────

    @Test
    fun `deleteTransaction calls softDelete with current txn id`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteTransaction()
        advanceUntilIdle()

        coVerify(exactly = 1) { pillCountTxnDao.softDelete(eq(7L), any()) }
    }

    @Test
    fun `deleteTransaction swallows exception from dao`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.softDelete(eq(7L), any()) } throws RuntimeException("db error")

        val vm = createViewModel()
        advanceUntilIdle()

        // Should not throw.
        vm.deleteTransaction()
        advanceUntilIdle()

        coVerify(exactly = 1) { pillCountTxnDao.softDelete(eq(7L), any()) }
    }

    // ────────────────────────────── getCurrentUser ──────────────────────────────

    @Test
    fun `getCurrentUser returns first recent login when available`() = runTest(testDispatcher) {
        every { preferenceHelper.getRecentLogins() } returns listOf("alice", "bob")

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("alice", vm.getCurrentUser())
    }

    @Test
    fun `getCurrentUser falls back to userId when no recent logins`() = runTest(testDispatcher) {
        every { preferenceHelper.getRecentLogins() } returns emptyList()
        every { preferenceHelper.getUserId() } returns "charlie"

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("charlie", vm.getCurrentUser())
    }

    @Test
    fun `getCurrentUser returns dash when nothing available`() = runTest(testDispatcher) {
        every { preferenceHelper.getRecentLogins() } returns emptyList()
        every { preferenceHelper.getUserId() } returns null

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("—", vm.getCurrentUser())
    }
}
