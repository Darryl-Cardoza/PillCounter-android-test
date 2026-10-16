package com.dispensesure.retail.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DispenseCountDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dispenseCountDao: DispenseCountDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dispenseCountDao = db.dispenseCountDao()

        // Detail rows require a parent dispense_txn row (FK).
        runTest {
            db.dispenseTxnDao().insertIgnore(
                DispenseTxnEntity(txnId = 1L, status = CountStatus.PARTIAL)
            )
            db.dispenseTxnDao().insertIgnore(
                DispenseTxnEntity(txnId = 2L, status = CountStatus.PARTIAL)
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ───────────────────────── insert / insertAll ─────────────────────────

    @Test
    fun `insert returns generated row id for new detail`() = runTest {
        val id = dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 10))
        assertTrue(id > 0L)
    }

    @Test
    fun `insert replaces existing row with same primary key`() = runTest {
        val id = dispenseCountDao.insert(DispenseCountEntity(countId = 5L, txnId = 1L, pillCount = 10))
        dispenseCountDao.insert(DispenseCountEntity(countId = 5L, txnId = 1L, pillCount = 99))

        val all = dispenseCountDao.getAllForTxn("1")
        assertEquals(1, all.size)
        assertEquals(99, all[0].pillCount)
        assertEquals(id, all[0].countId)
    }

    @Test
    fun `insertAll inserts multiple details and returns their ids`() = runTest {
        val ids = dispenseCountDao.insertAll(
            listOf(
                DispenseCountEntity(txnId = 1L, pillCount = 1),
                DispenseCountEntity(txnId = 1L, pillCount = 2),
                DispenseCountEntity(txnId = 1L, pillCount = 3)
            )
        )
        assertEquals(3, ids.size)
        assertTrue(ids.all { it > 0L })
    }

    @Test
    fun `insertAll with empty list inserts nothing`() = runTest {
        val ids = dispenseCountDao.insertAll(emptyList())
        assertTrue(ids.isEmpty())
        assertEquals(0, dispenseCountDao.getAllForTxn("1").size)
    }

    // ───────────────────────── observeAllForTxn ─────────────────────────

    @Test
    fun `observeAllForTxn emits only non-deleted rows matching txn and type ordered newest first`() = runTest {
        dispenseCountDao.insert(
            DispenseCountEntity(
                txnId = 1L, pillCount = 1, step = StepState.VIAL.name, createdAt = 100L
            )
        )
        dispenseCountDao.insert(
            DispenseCountEntity(
                txnId = 1L, pillCount = 2, step = StepState.VIAL.name, createdAt = 200L
            )
        )
        // different type - excluded
        dispenseCountDao.insert(
            DispenseCountEntity(txnId = 1L, pillCount = 3, step = StepState.SCAN.name)
        )
        // different txn - excluded
        dispenseCountDao.insert(
            DispenseCountEntity(txnId = 2L, pillCount = 4, step = StepState.VIAL.name)
        )

        dispenseCountDao.observeAllForTxn(1L, StepState.VIAL).test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assertEquals(2, list[0].pillCount)
            assertEquals(1, list[1].pillCount)
        }
    }

    @Test
    fun `observeAllForTxn emits empty list when no matching rows exist`() = runTest {
        dispenseCountDao.observeAllForTxn(99L, StepState.VIAL).test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `observeAllForTxn re-emits after a new insert`() = runTest {
        dispenseCountDao.observeAllForTxn(1L, StepState.VIAL).test {
            assertTrue(awaitItem().isEmpty())

            dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 7, step = StepState.VIAL.name))

            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals(7, updated[0].pillCount)
        }
    }

    // ───────────────────────── hardDelete ─────────────────────────

    @Test
    fun `hardDelete removes only that row`() = runTest {
        val id = dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 3))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 4))

        dispenseCountDao.hardDelete(id)

        val remaining = dispenseCountDao.getAllForTxn("1")
        assertEquals(1, remaining.size)
        assertEquals(4, remaining[0].pillCount)
    }

    @Test
    fun `hardDelete on nonexistent id affects no rows`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 3))
        dispenseCountDao.hardDelete(999L)
        assertEquals(3, dispenseCountDao.getTotalPillCountForTxn(1L))
    }

    // ───────────────────────── image paths ─────────────────────────

    @Test
    fun `getImagePathsForCount returns processed and raw paths of that row only`() = runTest {
        val id = dispenseCountDao.insert(
            DispenseCountEntity(txnId = 1L, imagePath = "/a.jpg", rawImagePath = "/a_raw.jpg")
        )
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, imagePath = "/b.jpg"))

        assertEquals(listOf("/a.jpg", "/a_raw.jpg"), dispenseCountDao.getImagePathsForCount(id).sorted())
    }

    // ───────────────────────── deleteVialByTxnId ─────────────────────────

    @Test
    fun `deleteVialByTxnId physically removes matching rows`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 5, step = StepState.VIAL.name))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 5, step = StepState.SCAN.name))

        dispenseCountDao.deleteVialByTxnId(txnId = 1L, step = StepState.VIAL)

        val remaining = dispenseCountDao.getAllForTxn("1")
        assertEquals(1, remaining.size)
        assertEquals(StepState.SCAN.name, remaining[0].step)
    }

    @Test
    fun `deleteVialByTxnId on nonexistent txnId is a no-op`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 5, step = StepState.VIAL.name))
        dispenseCountDao.deleteVialByTxnId(txnId = 404L, step = StepState.VIAL)
        assertEquals(1, dispenseCountDao.getAllForTxn("1").size)
    }

    // ───────────────────────── reset: hard delete ─────────────────────────

    @Test
    fun `deleteAllForTxn physically removes every detail for that txn only`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 3, step = StepState.SCAN.name))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 4, step = StepState.VIAL.name))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 2L, pillCount = 9, step = StepState.SCAN.name))

        dispenseCountDao.deleteAllForTxn(1L)

        assertEquals(0, dispenseCountDao.getAllForTxn("1").size)
        assertEquals(1, dispenseCountDao.getAllForTxn("2").size)
    }

    @Test
    fun `getImagePathsForTxn returns only the non-null paths`() = runTest {
        dispenseCountDao.insert(
            DispenseCountEntity(
                txnId = 1L, imagePath = "/a.jpg", rawImagePath = "/a_raw.jpg", step = StepState.SCAN.name
            )
        )
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, imagePath = null, step = StepState.SCAN.name))
        dispenseCountDao.insert(
            DispenseCountEntity(txnId = 1L, imagePath = "/b.jpg", step = StepState.VIAL.name)
        )
        dispenseCountDao.insert(
            DispenseCountEntity(txnId = 2L, imagePath = "/other.jpg", step = StepState.SCAN.name)
        )

        assertEquals(listOf("/a.jpg", "/a_raw.jpg", "/b.jpg"), dispenseCountDao.getImagePathsForTxn(1L).sorted())
    }

    // ───────────────────────── getTotalPillCountForTxn ─────────────────────────

    @Test
    fun `getTotalPillCountForTxn returns zero when no details exist`() = runTest {
        assertEquals(0, dispenseCountDao.getTotalPillCountForTxn(1L))
    }

    @Test
    fun `getTotalPillCountForTxn sums pillCount for that txn only`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 10))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 20))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 2L, pillCount = 1000))

        assertEquals(30, dispenseCountDao.getTotalPillCountForTxn(1L))
    }

    @Test
    fun `getTotalPillCountForTxn treats null pillCount as zero contribution`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = null))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 4))

        assertEquals(4, dispenseCountDao.getTotalPillCountForTxn(1L))
    }

    // ───────────────────────── getPillCountForCountIds ─────────────────────────

    @Test
    fun `getPillCountForCountIds returns zero for empty id list`() = runTest {
        assertEquals(0, dispenseCountDao.getPillCountForCountIds(emptyList()))
    }

    @Test
    fun `getPillCountForCountIds sums only the requested ids`() = runTest {
        val id1 = dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 10))
        val id2 = dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 20))
        val id3 = dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 30))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 999)) // not in list

        val total = dispenseCountDao.getPillCountForCountIds(listOf(id1, id2, id3))
        assertEquals(60, total)
    }

    @Test
    fun `getPillCountForCountIds returns zero when no ids match`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 10))
        assertEquals(0, dispenseCountDao.getPillCountForCountIds(listOf(9999L)))
    }

    // ───────────────────────── getAllForTxn ─────────────────────────

    @Test
    fun `getAllForTxn returns the txn's rows ordered by createdAt descending`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 1, createdAt = 100L))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 2, createdAt = 300L))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 3, createdAt = 200L))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 2L, pillCount = 5))

        val result = dispenseCountDao.getAllForTxn("1")
        assertEquals(3, result.size)
        assertEquals(2, result[0].pillCount)
        assertEquals(3, result[1].pillCount)
        assertEquals(1, result[2].pillCount)
    }

    @Test
    fun `getAllForTxn returns empty list for txn with no details`() = runTest {
        assertTrue(dispenseCountDao.getAllForTxn("1").isEmpty())
    }

    // ───────────────────────── getLatestStep ─────────────────────────

    @Test
    fun `getLatestStep returns null when no details exist for txn`() = runTest {
        assertNull(dispenseCountDao.getLatestStep(1L))
    }

    @Test
    fun `getLatestStep returns type of the most recently created row`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, step = StepState.SCAN.name, createdAt = 100L))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, step = StepState.VIAL.name, createdAt = 300L))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, step = StepState.RX_LABEL.name, createdAt = 200L))

        assertEquals(StepState.VIAL, dispenseCountDao.getLatestStep(1L))
    }

    @Test
    fun `getLatestStep returns null when stored type column is null`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, step = null, createdAt = 100L))
        assertNull(dispenseCountDao.getLatestStep(1L))
    }

    // ───────────────────────── getPillCountForStep ─────────────────────────

    @Test
    fun `getPillCountForStep sums only rows of that step`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 60, step = StepState.CONTAINER_INITIATE.name))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 20, step = StepState.TARGET_VERIFICATION.name))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 3, step = StepState.TARGET_VERIFICATION.name))
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 23, step = StepState.TARGET_REVERIFICATION.name))

        assertEquals(23, dispenseCountDao.getPillCountForStep(1L, StepState.TARGET_VERIFICATION.name))
        // The all-steps sum still spans every row — other callers rely on that.
        assertEquals(106, dispenseCountDao.getTotalPillCountForTxn(1L))
    }

    @Test
    fun `getPillCountForStep returns zero when the step has no rows`() = runTest {
        dispenseCountDao.insert(DispenseCountEntity(txnId = 1L, pillCount = 60, step = StepState.CONTAINER_INITIATE.name))

        assertEquals(0, dispenseCountDao.getPillCountForStep(1L, StepState.TARGET_VERIFICATION.name))
    }
}
