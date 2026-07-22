package com.rite.pillcounting.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.enums.CountStatus
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
class PillCountTxnDetailsDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PillCountTxnDetailsDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.pillCountTxnDetailsDao()

        // Detail rows require a parent pill_count_txn row (FK).
        runTest {
            db.pillCountTxnDao().insertIgnore(
                PillCountTxnEntity(txnId = 1L, isDispense = false, status = CountStatus.PARTIAL)
            )
            db.pillCountTxnDao().insertIgnore(
                PillCountTxnEntity(txnId = 2L, isDispense = false, status = CountStatus.PARTIAL)
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
        val id = dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 10))
        assertTrue(id > 0L)
    }

    @Test
    fun `insert replaces existing row with same primary key`() = runTest {
        val id = dao.insert(PillCountTxnDetailsEntity(txnDetailsId = 5L, txnId = 1L, pillCount = 10))
        dao.insert(PillCountTxnDetailsEntity(txnDetailsId = 5L, txnId = 1L, pillCount = 99))

        val all = dao.getAllForTxn("1")
        assertEquals(1, all.size)
        assertEquals(99, all[0].pillCount)
        assertEquals(id, all[0].txnDetailsId)
    }

    @Test
    fun `insertAll inserts multiple details and returns their ids`() = runTest {
        val ids = dao.insertAll(
            listOf(
                PillCountTxnDetailsEntity(txnId = 1L, pillCount = 1),
                PillCountTxnDetailsEntity(txnId = 1L, pillCount = 2),
                PillCountTxnDetailsEntity(txnId = 1L, pillCount = 3)
            )
        )
        assertEquals(3, ids.size)
        assertTrue(ids.all { it > 0L })
    }

    @Test
    fun `insertAll with empty list inserts nothing`() = runTest {
        val ids = dao.insertAll(emptyList())
        assertTrue(ids.isEmpty())
        assertEquals(0, dao.getAllForTxn("1").size)
    }

    // ───────────────────────── observeAllForTxn ─────────────────────────

    @Test
    fun `observeAllForTxn emits only non-deleted rows matching txn and type ordered newest first`() = runTest {
        dao.insert(
            PillCountTxnDetailsEntity(
                txnId = 1L, pillCount = 1, type = StepState.VIAL.name, createdAt = 100L
            )
        )
        dao.insert(
            PillCountTxnDetailsEntity(
                txnId = 1L, pillCount = 2, type = StepState.VIAL.name, createdAt = 200L
            )
        )
        // different type - excluded
        dao.insert(
            PillCountTxnDetailsEntity(txnId = 1L, pillCount = 3, type = StepState.SCAN.name)
        )
        // different txn - excluded
        dao.insert(
            PillCountTxnDetailsEntity(txnId = 2L, pillCount = 4, type = StepState.VIAL.name)
        )
        // soft-deleted - excluded
        dao.insert(
            PillCountTxnDetailsEntity(
                txnId = 1L, pillCount = 5, type = StepState.VIAL.name, isDeleted = true
            )
        )

        dao.observeAllForTxn(1L, StepState.VIAL).test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assertEquals(2, list[0].pillCount)
            assertEquals(1, list[1].pillCount)
        }
    }

    @Test
    fun `observeAllForTxn emits empty list when no matching rows exist`() = runTest {
        dao.observeAllForTxn(99L, StepState.VIAL).test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `observeAllForTxn re-emits after a new insert`() = runTest {
        dao.observeAllForTxn(1L, StepState.VIAL).test {
            assertTrue(awaitItem().isEmpty())

            dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 7, type = StepState.VIAL.name))

            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals(7, updated[0].pillCount)
        }
    }

    // ───────────────────────── softDelete ─────────────────────────

    @Test
    fun `softDelete marks row deleted and updates timestamp using provided now`() = runTest {
        val id = dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 3, updatedAt = 0L))
        dao.softDelete(id, now = 555L)

        val all = dao.getAllForTxn("1")
        assertTrue(all.isEmpty()) // getAllForTxn excludes isDeleted rows

        // Verify via total pill count query which also excludes deleted rows.
        assertEquals(0, dao.getTotalPillCountForTxn(1L))
    }

    @Test
    fun `softDelete with default now uses current time and does not throw`() = runTest {
        val id = dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 3))
        dao.softDelete(id)
        assertEquals(0, dao.getTotalPillCountForTxn(1L))
    }

    @Test
    fun `softDelete on nonexistent id affects no rows`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 3))
        dao.softDelete(id = 999L, now = 1L)
        assertEquals(3, dao.getTotalPillCountForTxn(1L))
    }

    // ───────────────────────── softDeleteAllTransaction ─────────────────────────

    @Test
    fun `softDeleteAllTransaction marks all rows of matching txn and type as deleted`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 5, type = StepState.VIAL.name))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 5, type = StepState.VIAL.name))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 5, type = StepState.SCAN.name))

        dao.softDeleteAllTransaction(id = 1L, now = 111L, type = StepState.VIAL)

        dao.observeAllForTxn(1L, StepState.VIAL).test {
            assertTrue(awaitItem().isEmpty())
        }
        dao.observeAllForTxn(1L, StepState.SCAN).test {
            assertEquals(1, awaitItem().size)
        }
    }

    @Test
    fun `softDeleteAllTransaction does not affect a different txnId`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 5, type = StepState.VIAL.name))
        dao.insert(PillCountTxnDetailsEntity(txnId = 2L, pillCount = 5, type = StepState.VIAL.name))

        dao.softDeleteAllTransaction(id = 1L, now = 111L, type = StepState.VIAL)

        dao.observeAllForTxn(2L, StepState.VIAL).test {
            assertEquals(1, awaitItem().size)
        }
    }

    // ───────────────────────── deleteVialByTxnId ─────────────────────────

    @Test
    fun `deleteVialByTxnId physically removes matching rows`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 5, type = StepState.VIAL.name))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 5, type = StepState.SCAN.name))

        dao.deleteVialByTxnId(txnId = 1L, type = StepState.VIAL)

        val remaining = dao.getAllForTxn("1")
        assertEquals(1, remaining.size)
        assertEquals(StepState.SCAN.name, remaining[0].type)
    }

    @Test
    fun `deleteVialByTxnId on nonexistent txnId is a no-op`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 5, type = StepState.VIAL.name))
        dao.deleteVialByTxnId(txnId = 404L, type = StepState.VIAL)
        assertEquals(1, dao.getAllForTxn("1").size)
    }

    // ───────────────────────── getTotalPillCountForTxn ─────────────────────────

    @Test
    fun `getTotalPillCountForTxn returns zero when no details exist`() = runTest {
        assertEquals(0, dao.getTotalPillCountForTxn(1L))
    }

    @Test
    fun `getTotalPillCountForTxn sums pillCount across non-deleted rows only`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 10))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 20))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 5, isDeleted = true))
        dao.insert(PillCountTxnDetailsEntity(txnId = 2L, pillCount = 1000))

        assertEquals(30, dao.getTotalPillCountForTxn(1L))
    }

    @Test
    fun `getTotalPillCountForTxn treats null pillCount as zero contribution`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = null))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 4))

        assertEquals(4, dao.getTotalPillCountForTxn(1L))
    }

    // ───────────────────────── getPillCountForDetailIds ─────────────────────────

    @Test
    fun `getPillCountForDetailIds returns zero for empty id list`() = runTest {
        assertEquals(0, dao.getPillCountForDetailIds(emptyList()))
    }

    @Test
    fun `getPillCountForDetailIds sums only the requested ids excluding deleted`() = runTest {
        val id1 = dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 10))
        val id2 = dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 20))
        val id3 = dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 30, isDeleted = true))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 999)) // not in list

        val total = dao.getPillCountForDetailIds(listOf(id1, id2, id3))
        assertEquals(30, total) // id3 excluded because isDeleted
    }

    @Test
    fun `getPillCountForDetailIds returns zero when no ids match`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 10))
        assertEquals(0, dao.getPillCountForDetailIds(listOf(9999L)))
    }

    // ───────────────────────── getAllForTxn ─────────────────────────

    @Test
    fun `getAllForTxn returns non-deleted rows ordered by createdAt descending`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 1, createdAt = 100L))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 2, createdAt = 300L))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 3, createdAt = 200L))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 4, isDeleted = true))
        dao.insert(PillCountTxnDetailsEntity(txnId = 2L, pillCount = 5))

        val result = dao.getAllForTxn("1")
        assertEquals(3, result.size)
        assertEquals(2, result[0].pillCount)
        assertEquals(3, result[1].pillCount)
        assertEquals(1, result[2].pillCount)
    }

    @Test
    fun `getAllForTxn returns empty list for txn with no details`() = runTest {
        assertTrue(dao.getAllForTxn("1").isEmpty())
    }

    // ───────────────────────── getLatestType ─────────────────────────

    @Test
    fun `getLatestType returns null when no details exist for txn`() = runTest {
        assertNull(dao.getLatestType(1L))
    }

    @Test
    fun `getLatestType returns type of the most recently created non-deleted row`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, type = StepState.SCAN.name, createdAt = 100L))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, type = StepState.VIAL.name, createdAt = 300L))
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, type = StepState.RX_LABEL.name, createdAt = 200L))

        assertEquals(StepState.VIAL, dao.getLatestType(1L))
    }

    @Test
    fun `getLatestType ignores soft-deleted rows even if most recent`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, type = StepState.SCAN.name, createdAt = 100L))
        dao.insert(
            PillCountTxnDetailsEntity(
                txnId = 1L, type = StepState.VIAL.name, createdAt = 500L, isDeleted = true
            )
        )

        assertEquals(StepState.SCAN, dao.getLatestType(1L))
    }

    @Test
    fun `getLatestType returns null when stored type column is null`() = runTest {
        dao.insert(PillCountTxnDetailsEntity(txnId = 1L, type = null, createdAt = 100L))
        assertNull(dao.getLatestType(1L))
    }
}
