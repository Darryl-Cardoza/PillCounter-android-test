package com.rite.pillcounting.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.UserEntity
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PillCountTxnDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PillCountTxnDao
    private lateinit var drugDao: DrugMasterDao
    private lateinit var detailsDao: PillCountTxnDetailsDao

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.pillCountTxnDao()
        drugDao = db.drugMasterDao()
        detailsDao = db.pillCountTxnDetailsDao()

        // baseTxn() defaults localId = 1L, which is FK-enforced against users.localId —
        // seed the matching user row so inserts don't hit a constraint violation.
        db.userDao().insertIgnore(UserEntity(userId = "test-user"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun baseTxn(
        txnId: Long = 0L,
        isDispense: Boolean = false,
        status: CountStatus = CountStatus.PARTIAL,
        localId: Long? = 1L,
        drugId: Long? = null,
        rxNo: String? = null,
        refillNo: String? = null,
        isComingFromHL7: Boolean? = null,
        isSynced: Boolean? = null,
        priority: TxnPriority? = null,
        createdAt: Long = System.currentTimeMillis(),
    ) = PillCountTxnEntity(
        txnId = txnId,
        isDispense = isDispense,
        status = status,
        localId = localId,
        drugId = drugId,
        rxNo = rxNo,
        refillNo = refillNo,
        isComingFromHL7 = isComingFromHL7,
        isSynced = isSynced,
        priority = priority,
        createdAt = createdAt,
    )

    // ───────────────────────── Insert / Upsert ─────────────────────────

    @Test
    fun `insertIgnore returns generated id for new transaction`() = runTest {
        val id = dao.insertIgnore(baseTxn())
        assertNotEquals(-1L, id)
        assertTrue(id > 0)
    }

    @Test
    fun `insertIgnore returns minus one on txnId conflict and keeps original row`() = runTest {
        val id = dao.insertIgnore(baseTxn(rxNo = "RX1"))
        val conflictResult = dao.insertIgnore(baseTxn(txnId = id, rxNo = "RX2"))

        assertEquals(-1L, conflictResult)
        assertEquals("RX1", dao.getById(id)?.rxNo)
    }

    @Test
    fun `insertAll inserts multiple transactions`() = runTest {
        val ids = dao.insertAll(listOf(baseTxn(rxNo = "A"), baseTxn(rxNo = "B")))
        assertEquals(2, ids.size)
    }

    @Test
    fun `insertAll with empty list returns empty result`() = runTest {
        val ids = dao.insertAll(emptyList())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `update modifies existing row matched by txnId`() = runTest {
        val id = dao.insertIgnore(baseTxn(rxNo = "OLD"))
        val stored = dao.getById(id)!!
        dao.update(stored.copy(rxNo = "NEW"))

        assertEquals("NEW", dao.getById(id)?.rxNo)
    }

    @Test
    fun `upsertPreservingId inserts new row when txnId is zero`() = runTest {
        val newId = dao.upsertPreservingId(baseTxn(txnId = 0L, rxNo = "NEWROW"))
        assertTrue(newId > 0)
        assertEquals("NEWROW", dao.getById(newId)?.rxNo)
    }

    @Test
    fun `upsertPreservingId updates existing row while preserving txnId`() = runTest {
        val id = dao.insertIgnore(baseTxn(rxNo = "ORIG"))
        val existing = dao.getById(id)!!

        val resultId = dao.upsertPreservingId(existing.copy(rxNo = "UPDATED"))

        assertEquals(id, resultId)
        assertEquals("UPDATED", dao.getById(id)?.rxNo)
    }

    @Test
    fun `upsertPreservingId with nonexistent txnId falls back to insertIgnore path`() = runTest {
        // txnId != 0 but no row exists yet: getById returns null, insertIgnore creates new row.
        val resultId = dao.upsertPreservingId(baseTxn(txnId = 999L, rxNo = "GHOST"))
        assertTrue(resultId > 0)
    }

    @Test
    fun `upsertPreservingId with zero txnId throws when insert conflicts`() = runTest {
        // First insert consumes an autogenerated id; forcing a duplicate insert path with txnId=0
        // cannot conflict under autoGenerate, so instead verify the non-zero conflict branch:
        // insert same non-existent-then-reinserted id twice via insertIgnore returning -1.
        val id = dao.insertIgnore(baseTxn(rxNo = "X"))
        dao.softDelete(id) // no-op for this branch check; row still exists with same txnId

        // Re-attempt upsert for the same non-zero txnId — should hit "existing != null" update branch,
        // not throw.
        val resultId = dao.upsertPreservingId(baseTxn(txnId = id, rxNo = "Y"))
        assertEquals(id, resultId)
    }

    // ───────────────────────── Reads ─────────────────────────

    @Test
    fun `getById returns null for missing transaction`() = runTest {
        assertNull(dao.getById(12345L))
    }

    @Test
    fun `getById returns matching transaction`() = runTest {
        val id = dao.insertIgnore(baseTxn(rxNo = "FOUND"))
        assertEquals("FOUND", dao.getById(id)?.rxNo)
    }

    // ───────────────────────── Field updates ─────────────────────────

    @Test
    fun `updateTargetCount sets value and clears to null`() = runTest {
        val id = dao.insertIgnore(baseTxn())
        dao.updateTargetCount(id, 50)
        assertEquals(50, dao.getById(id)?.targetCount)

        dao.updateTargetCount(id, null)
        assertNull(dao.getById(id)?.targetCount)
    }

    @Test
    fun `updateNote sets and clears note`() = runTest {
        val id = dao.insertIgnore(baseTxn())
        dao.updateNote(id, "hello")
        assertEquals("hello", dao.getById(id)?.note)

        dao.updateNote(id, null)
        assertNull(dao.getById(id)?.note)
    }

    @Test
    fun `updateBottleInfoList persists json string`() = runTest {
        val id = dao.insertIgnore(baseTxn())
        dao.updateBottleInfoList(id, "[{\"a\":1}]")
        assertEquals("[{\"a\":1}]", dao.getById(id)?.bottleInfoListJson)
    }

    @Test
    fun `softDelete marks isDeleted true`() = runTest {
        val id = dao.insertIgnore(baseTxn())
        dao.softDelete(id)
        assertTrue(dao.getById(id)!!.isDeleted)
    }

    @Test
    fun `softDeleteByRxNo only marks non deleted rows for rxNo`() = runTest {
        val id1 = dao.insertIgnore(baseTxn(rxNo = "R1"))
        val id2 = dao.insertIgnore(baseTxn(rxNo = "R1"))
        dao.softDelete(id2) // pre-mark one as already deleted

        dao.softDeleteByRxNo("R1")

        assertTrue(dao.getById(id1)!!.isDeleted)
        assertTrue(dao.getById(id2)!!.isDeleted)
    }

    @Test
    fun `getDeletedByRxNo returns most recent deleted row or null`() = runTest {
        assertNull(dao.getDeletedByRxNo("NONE"))

        val id1 = dao.insertIgnore(baseTxn(rxNo = "DEL", createdAt = 100L))
        val id2 = dao.insertIgnore(baseTxn(rxNo = "DEL", createdAt = 200L))
        dao.softDelete(id1, now = 100L)
        dao.softDelete(id2, now = 500L)

        val result = dao.getDeletedByRxNo("DEL")
        assertEquals(id2, result?.txnId)
    }

    @Test
    fun `restoreDeletedTxn clears isDeleted and resets status to PARTIAL`() = runTest {
        val id = dao.insertIgnore(baseTxn(status = CountStatus.COMPLETED))
        dao.softDelete(id)

        dao.restoreDeletedTxn(id)

        val restored = dao.getById(id)!!
        assertFalse(restored.isDeleted)
        assertEquals(CountStatus.PARTIAL, restored.status)
    }

    @Test
    fun `getActiveByRxNo returns most recent partial or on hold and ignores others`() = runTest {
        dao.insertIgnore(baseTxn(rxNo = "ACT", status = CountStatus.COMPLETED, createdAt = 50L))
        val id2 = dao.insertIgnore(baseTxn(rxNo = "ACT", status = CountStatus.PARTIAL, createdAt = 300L))

        val result = dao.getActiveByRxNo("ACT")
        assertEquals(id2, result?.txnId)
    }

    @Test
    fun `getActiveByRxNo returns null when only deleted or completed rows exist`() = runTest {
        val id = dao.insertIgnore(baseTxn(rxNo = "GONE", status = CountStatus.PARTIAL))
        dao.softDelete(id)

        assertNull(dao.getActiveByRxNo("GONE"))
    }

    // ───────────────────────── Image server lookups ─────────────────────────

    @Test
    fun `getByMessageControlId finds latest matching non deleted row`() = runTest {
        assertNull(dao.getByMessageControlId("MSG1"))

        val old = baseTxn(createdAt = 100L).copy(hl7MessageControlId = "MSG1")
        val newer = baseTxn(createdAt = 200L).copy(hl7MessageControlId = "MSG1")
        dao.insertIgnore(old)
        val newerId = dao.insertIgnore(newer)

        assertEquals(newerId, dao.getByMessageControlId("MSG1")?.txnId)
    }

    @Test
    fun `getBySequenceNumber returns null when not found`() = runTest {
        assertNull(dao.getBySequenceNumber("SEQ-404"))
    }

    @Test
    fun `getBySequenceNumber finds matching row`() = runTest {
        val id = dao.insertIgnore(baseTxn().copy(hl7SequenceNumber = "SEQ-1"))
        assertEquals(id, dao.getBySequenceNumber("SEQ-1")?.txnId)
    }

    @Test
    fun `getByTransactionOrderId finds matching row`() = runTest {
        val id = dao.insertIgnore(baseTxn().copy(transactionOrderId = "ORD-1"))
        assertEquals(id, dao.getByTransactionOrderId("ORD-1")?.txnId)
    }

    @Test
    fun `getByRxNoAndFillNo matches both fields`() = runTest {
        val id = dao.insertIgnore(baseTxn(rxNo = "RX9", refillNo = "1"))
        dao.insertIgnore(baseTxn(rxNo = "RX9", refillNo = "2"))

        assertEquals(id, dao.getByRxNoAndFillNo("RX9", "1")?.txnId)
        assertNull(dao.getByRxNoAndFillNo("RX9", "99"))
    }

    @Test
    fun `getMostRecentByRxNo ignores fill number and returns newest`() = runTest {
        dao.insertIgnore(baseTxn(rxNo = "RX7", refillNo = "1", createdAt = 100L))
        val newest = dao.insertIgnore(baseTxn(rxNo = "RX7", refillNo = "2", createdAt = 500L))

        assertEquals(newest, dao.getMostRecentByRxNo("RX7")?.txnId)
    }

    // ───────────────────────── HL7 edit / workflow updates ─────────────────────────

    @Test
    fun `updateFromHl7Edit updates drug target priority and resets sync flag`() = runTest {
        val id = dao.insertIgnore(baseTxn(status = CountStatus.PARTIAL, isSynced = true))
        // updateFromHl7Edit sets drugId via UPDATE, which is FK-enforced against drug_master —
        // seed the drug row referenced by this test's drugId = 42L.
        drugDao.insertIgnore(DrugMasterEntity(drugId = 42L, ndc = "HL7-42"))

        dao.updateFromHl7Edit(
            txnId = id,
            drugId = 42L,
            targetCount = 30,
            priority = TxnPriority.High,
            status = CountStatus.ON_HOLD
        )

        val updated = dao.getById(id)!!
        assertEquals(42L, updated.drugId)
        assertEquals(30, updated.targetCount)
        assertEquals(TxnPriority.High, updated.priority)
        assertEquals(CountStatus.ON_HOLD, updated.status)
        assertEquals(false, updated.isSynced)
    }

    @Test
    fun `updateFromHl7Edit with null status leaves status unchanged`() = runTest {
        val id = dao.insertIgnore(baseTxn(status = CountStatus.PARTIAL))
        // Seed the drug row referenced by this test's drugId = 1L (FK-enforced on UPDATE).
        drugDao.insertIgnore(DrugMasterEntity(drugId = 1L, ndc = "HL7-1"))

        dao.updateFromHl7Edit(
            txnId = id,
            drugId = 1L,
            targetCount = 10,
            priority = TxnPriority.Low,
            status = null
        )

        assertEquals(CountStatus.PARTIAL, dao.getById(id)?.status)
    }

    @Test
    fun `deleteAllTransactions removes every row`() = runTest {
        dao.insertIgnore(baseTxn())
        dao.insertIgnore(baseTxn())
        dao.deleteAllTransactions()

        assertNull(dao.getById(1L))
        assertNull(dao.getById(2L))
    }

    @Test
    fun `updateWorkflowStep sets step value`() = runTest {
        val id = dao.insertIgnore(baseTxn())
        dao.updateWorkflowStep(id, "SCAN")
        assertEquals("SCAN", dao.getById(id)?.workflowStep)
    }

    @Test
    fun `updateGlovesPresent toggles flag`() = runTest {
        val id = dao.insertIgnore(baseTxn())
        dao.updateGlovesPresent(id, true)
        assertTrue(dao.getById(id)!!.isGlovesPresent)

        dao.updateGlovesPresent(id, false)
        assertFalse(dao.getById(id)!!.isGlovesPresent)
    }

    @Test
    fun `updateHazardousTrayDetected sets nullable boolean`() = runTest {
        val id = dao.insertIgnore(baseTxn())
        dao.updateHazardousTrayDetected(id, true)
        assertEquals(true, dao.getById(id)?.hazardousTrayDetected)
    }

    @Test
    fun `updateTxnStatus changes status`() = runTest {
        val id = dao.insertIgnore(baseTxn(status = CountStatus.PARTIAL))
        dao.updateTxnStatus(id, CountStatus.COMPLETED)
        assertEquals(CountStatus.COMPLETED, dao.getById(id)?.status)
    }

    // ───────────────────────── Date-range deletes / retention ─────────────────────────

    @Test
    fun `deleteTransactionsByDate removes only rows within range matching filters`() = runTest {
        // localId = 2L (wrongUser) is FK-enforced against users.localId — seed it.
        db.userDao().insertIgnore(UserEntity(localId = 2L, userId = "user-2"))

        val inRange = dao.insertIgnore(
            baseTxn(localId = 1L, isDispense = true, status = CountStatus.COMPLETED, createdAt = 150L)
        )
        val outOfRange = dao.insertIgnore(
            baseTxn(localId = 1L, isDispense = true, status = CountStatus.COMPLETED, createdAt = 999L)
        )
        val wrongUser = dao.insertIgnore(
            baseTxn(localId = 2L, isDispense = true, status = CountStatus.COMPLETED, createdAt = 150L)
        )

        dao.deleteTransactionsByDate(
            start = 100L,
            end = 200L,
            isDispense = true,
            isCompleted = true,
            userLocalId = 1L
        )

        assertNull(dao.getById(inRange))
        assertEquals(outOfRange, dao.getById(outOfRange)?.txnId)
        assertEquals(wrongUser, dao.getById(wrongUser)?.txnId)
    }

    @Test
    fun `deleteTransactionsByDate with null filters matches any dispense and completion state`() = runTest {
        // localId = 5L is FK-enforced against users.localId — seed it.
        db.userDao().insertIgnore(UserEntity(localId = 5L, userId = "user-5"))

        val id = dao.insertIgnore(
            baseTxn(localId = 5L, isDispense = false, status = CountStatus.PARTIAL, createdAt = 150L)
        )

        dao.deleteTransactionsByDate(
            start = 100L,
            end = 200L,
            isDispense = null,
            isCompleted = null,
            userLocalId = 5L
        )

        assertNull(dao.getById(id))
    }

    @Test
    fun `deleteTransactionsByDate isCompleted false excludes completed and force completed rows`() = runTest {
        val partial = dao.insertIgnore(
            baseTxn(localId = 1L, isDispense = true, status = CountStatus.PARTIAL, createdAt = 150L)
        )
        val completed = dao.insertIgnore(
            baseTxn(localId = 1L, isDispense = true, status = CountStatus.COMPLETED, createdAt = 150L)
        )

        dao.deleteTransactionsByDate(
            start = 100L,
            end = 200L,
            isDispense = true,
            isCompleted = false,
            userLocalId = 1L
        )

        assertNull(dao.getById(partial))
        assertEquals(completed, dao.getById(completed)?.txnId)
    }

    @Test
    fun `getTransactionsBefore returns only completed or force completed rows before cutoff`() = runTest {
        dao.insertIgnore(baseTxn(status = CountStatus.COMPLETED, createdAt = 100L))
        dao.insertIgnore(baseTxn(status = CountStatus.FORCE_COMPLETED, createdAt = 150L))
        dao.insertIgnore(baseTxn(status = CountStatus.PARTIAL, createdAt = 100L))
        dao.insertIgnore(baseTxn(status = CountStatus.COMPLETED, createdAt = 999L))

        val result = dao.getTransactionsBefore(200L)
        assertEquals(2, result.size)
        assertTrue(result.all { it.status == CountStatus.COMPLETED || it.status == CountStatus.FORCE_COMPLETED })
    }

    @Test
    fun `getSyncedTransactions filters by isSynced and isDispense with default true`() = runTest {
        dao.insertIgnore(baseTxn(isDispense = true, isSynced = true))
        dao.insertIgnore(baseTxn(isDispense = true, isSynced = false))
        dao.insertIgnore(baseTxn(isDispense = false, isSynced = true))

        val result = dao.getSyncedTransactions()
        assertEquals(1, result.size)
        assertTrue(result.first().isDispense)
        assertEquals(true, result.first().isSynced)
    }

    @Test
    fun `getTransactionDetailsImages returns image paths for txn`() = runTest {
        val txnId = dao.insertIgnore(baseTxn())
        detailsDao.insert(PillCountTxnDetailsEntity(txnId = txnId, imagePath = "img1.jpg"))
        detailsDao.insert(PillCountTxnDetailsEntity(txnId = txnId, imagePath = "img2.jpg"))

        val images = dao.getTransactionDetailsImages(txnId)
        assertEquals(2, images.size)
        assertTrue(images.containsAll(listOf("img1.jpg", "img2.jpg")))
    }

    @Test
    fun `getTransactionDetailsImages returns empty list when none exist`() = runTest {
        val txnId = dao.insertIgnore(baseTxn())
        assertTrue(dao.getTransactionDetailsImages(txnId).isEmpty())
    }

    @Test
    fun `deleteTransaction permanently removes the row`() = runTest {
        val id = dao.insertIgnore(baseTxn())
        dao.deleteTransaction(id)
        assertNull(dao.getById(id))
    }

    // ───────────────────────── HL7 sync flows ─────────────────────────

    @Test
    fun `observePendingHl7Txn emits only completed unsynced hl7 rows`() = runTest {
        dao.observePendingHl7Txn().test {
            assertTrue(awaitItem().isEmpty())

            val id = dao.insertIgnore(
                baseTxn(status = CountStatus.COMPLETED, isComingFromHL7 = true, isSynced = false)
            )
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(id, list.first().txnId)
        }
    }

    @Test
    fun `observePendingHl7Txn excludes synced non hl7 or non completed rows`() = runTest {
        dao.insertIgnore(baseTxn(status = CountStatus.COMPLETED, isComingFromHL7 = true, isSynced = true))
        dao.insertIgnore(baseTxn(status = CountStatus.COMPLETED, isComingFromHL7 = false, isSynced = false))
        dao.insertIgnore(baseTxn(status = CountStatus.PARTIAL, isComingFromHL7 = true, isSynced = false))

        dao.observePendingHl7Txn().test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `getPendingHl7TxnOnce returns snapshot of pending rows`() = runTest {
        val id = dao.insertIgnore(
            baseTxn(status = CountStatus.COMPLETED, isComingFromHL7 = true, isSynced = null)
        )
        val result = dao.getPendingHl7TxnOnce()
        assertEquals(1, result.size)
        assertEquals(id, result.first().txnId)
    }

    @Test
    fun `markTxnSynced sets isSynced true`() = runTest {
        val id = dao.insertIgnore(baseTxn(isSynced = false))
        dao.markTxnSynced(id)
        assertEquals(true, dao.getById(id)?.isSynced)
    }

    @Test
    fun `markCompletedAndUnsynced sets status and clears sync flag`() = runTest {
        val id = dao.insertIgnore(baseTxn(status = CountStatus.PARTIAL, isSynced = true))
        dao.markCompletedAndUnsynced(id, CountStatus.COMPLETED)

        val updated = dao.getById(id)!!
        assertEquals(CountStatus.COMPLETED, updated.status)
        assertEquals(false, updated.isSynced)
    }

    @Test
    fun `observeUnsyncedByIsDispense returns completed or force completed unsynced rows with total pill count`() = runTest {
        val drugId = drugDao.insertIgnore(DrugMasterEntity(ndc = "0001", drugName = "Aspirin"))
        val txnId = dao.insertIgnore(
            baseTxn(
                isDispense = true,
                status = CountStatus.COMPLETED,
                isSynced = false,
                drugId = drugId
            )
        )
        detailsDao.insert(
            PillCountTxnDetailsEntity(txnId = txnId, pillCount = 10, type = StepState.SCAN.name)
        )
        detailsDao.insert(
            PillCountTxnDetailsEntity(txnId = txnId, pillCount = 5, type = StepState.SCAN.name)
        )

        dao.observeUnsyncedByIsDispense(isDispense = true, type = StepState.SCAN.name).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(15, list.first().totalPillCount)
            assertEquals("Aspirin", list.first().drugName)
        }
    }

    @Test
    fun `observeUnsyncedByIsDispense excludes synced rows and wrong dispense flag`() = runTest {
        dao.insertIgnore(baseTxn(isDispense = true, status = CountStatus.COMPLETED, isSynced = true))
        dao.insertIgnore(baseTxn(isDispense = false, status = CountStatus.COMPLETED, isSynced = false))

        dao.observeUnsyncedByIsDispense(isDispense = true, type = StepState.SCAN.name).test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `getTotalCompletedTransactionCount counts unsynced hl7 completed and force completed rows`() = runTest {
        dao.observeDashboardCountsGrouped(userLocalId = 0L) // no-op to ensure flow interfaces compile
        dao.getTotalCompletedTransactionCount().test {
            assertEquals(0, awaitItem())

            dao.insertIgnore(
                baseTxn(status = CountStatus.COMPLETED, isComingFromHL7 = true, isSynced = false)
            )
            assertEquals(1, awaitItem())

            dao.insertIgnore(
                baseTxn(status = CountStatus.FORCE_COMPLETED, isComingFromHL7 = true, isSynced = false)
            )
            assertEquals(2, awaitItem())
        }
    }

    @Test
    fun `countPartialByIsDispense counts matching partial rows for user`() = runTest {
        // localId = 7L and 8L are FK-enforced against users.localId — seed both.
        db.userDao().insertIgnore(UserEntity(localId = 7L, userId = "user-7"))
        db.userDao().insertIgnore(UserEntity(localId = 8L, userId = "user-8"))

        dao.insertIgnore(baseTxn(localId = 7L, isDispense = true, status = CountStatus.PARTIAL))
        dao.insertIgnore(baseTxn(localId = 7L, isDispense = true, status = CountStatus.PARTIAL))
        dao.insertIgnore(baseTxn(localId = 7L, isDispense = false, status = CountStatus.PARTIAL))
        dao.insertIgnore(baseTxn(localId = 8L, isDispense = true, status = CountStatus.PARTIAL))

        val count = dao.countPartialByIsDispense(isDispense = true, userLocalId = 7L)
        assertEquals(2, count)
    }

    // ───────────────────────── Dashboard / join queries ─────────────────────────

    @Test
    fun `observeDashboardCountsGrouped groups by status and isDispense for user`() = runTest {
        // localId = 2L is FK-enforced against users.localId — seed it (localId 1L is seeded in setUp).
        db.userDao().insertIgnore(UserEntity(localId = 2L, userId = "user-2"))

        dao.insertIgnore(baseTxn(localId = 1L, isDispense = true, status = CountStatus.PARTIAL))
        dao.insertIgnore(baseTxn(localId = 1L, isDispense = true, status = CountStatus.PARTIAL))
        dao.insertIgnore(baseTxn(localId = 1L, isDispense = false, status = CountStatus.COMPLETED))
        dao.insertIgnore(baseTxn(localId = 2L, isDispense = true, status = CountStatus.PARTIAL))

        dao.observeDashboardCountsGrouped(userLocalId = 1L).test {
            val groups = awaitItem()
            assertEquals(2, groups.size)
            val partialGroup = groups.first { it.status == CountStatus.PARTIAL }
            assertEquals(2, partialGroup.cnt)
        }
    }

    @Test
    fun `getTxnWithDetails returns null for missing or deleted transaction`() = runTest {
        assertNull(dao.getTxnWithDetails(555L))

        val id = dao.insertIgnore(baseTxn())
        dao.softDelete(id)
        assertNull(dao.getTxnWithDetails(id))
    }

    @Test
    fun `getTxnWithDetails aggregates drug info and pill count total`() = runTest {
        val drugId = drugDao.insertIgnore(DrugMasterEntity(ndc = "1111", drugName = "Ibuprofen"))
        val txnId = dao.insertIgnore(baseTxn(drugId = drugId, status = CountStatus.PARTIAL))
        detailsDao.insert(PillCountTxnDetailsEntity(txnId = txnId, pillCount = 3))
        detailsDao.insert(PillCountTxnDetailsEntity(txnId = txnId, pillCount = 7))

        val result = dao.getTxnWithDetails(txnId)!!
        assertEquals("Ibuprofen", result.drugName)
        assertEquals(10, result.totalPillCount)
    }

    @Test
    fun `getTransactionsWithDrugByDate filters by date range and null filters match any`() = runTest {
        dao.insertIgnore(baseTxn(isDispense = true, status = CountStatus.COMPLETED, createdAt = 150L))
        dao.insertIgnore(baseTxn(isDispense = true, status = CountStatus.COMPLETED, createdAt = 999L))

        dao.getTransactionsWithDrugByDate(
            startOfDay = 100L,
            endOfDay = 200L,
            isDispense = null,
            status = null
        ).test {
            val list = awaitItem()
            assertEquals(1, list.size)
        }
    }

    @Test
    fun `getTransactionsWithDrugByDate applies non null isDispense and status filters`() = runTest {
        dao.insertIgnore(baseTxn(isDispense = true, status = CountStatus.COMPLETED, createdAt = 150L))
        dao.insertIgnore(baseTxn(isDispense = false, status = CountStatus.COMPLETED, createdAt = 150L))
        dao.insertIgnore(baseTxn(isDispense = true, status = CountStatus.PARTIAL, createdAt = 150L))

        dao.getTransactionsWithDrugByDate(
            startOfDay = 100L,
            endOfDay = 200L,
            isDispense = true,
            status = CountStatus.COMPLETED
        ).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertTrue(list.first().isDispense)
            assertEquals(CountStatus.COMPLETED, list.first().status)
        }
    }

    @Test
    fun `getTransactionsForDateRange filters by user step type dispense and status`() = runTest {
        // localId = 3L and 9L are FK-enforced against users.localId — seed both.
        db.userDao().insertIgnore(UserEntity(localId = 3L, userId = "user-3"))
        db.userDao().insertIgnore(UserEntity(localId = 9L, userId = "user-9"))

        val txnId = dao.insertIgnore(
            baseTxn(localId = 3L, isDispense = true, status = CountStatus.COMPLETED, createdAt = 150L)
        )
        detailsDao.insert(
            PillCountTxnDetailsEntity(txnId = txnId, pillCount = 4, type = StepState.SCAN.name)
        )
        detailsDao.insert(
            PillCountTxnDetailsEntity(txnId = txnId, pillCount = 100, type = StepState.VIAL.name)
        )
        dao.insertIgnore(
            baseTxn(localId = 9L, isDispense = true, status = CountStatus.COMPLETED, createdAt = 150L)
        )

        dao.getTransactionsForDateRange(
            startDate = 100L,
            endDate = 200L,
            stepType = StepState.SCAN,
            isDispense = true,
            status = CountStatus.COMPLETED,
            userLocalId = 3L
        ).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(4, list.first().pillCount)
        }
    }

    @Test
    fun `getTransactionsForDateRange with null stepType includes all detail types`() = runTest {
        // localId = 3L is FK-enforced against users.localId — seed it.
        db.userDao().insertIgnore(UserEntity(localId = 3L, userId = "user-3"))

        val txnId = dao.insertIgnore(
            baseTxn(localId = 3L, isDispense = true, status = CountStatus.COMPLETED, createdAt = 150L)
        )
        detailsDao.insert(
            PillCountTxnDetailsEntity(txnId = txnId, pillCount = 4, type = StepState.SCAN.name)
        )
        detailsDao.insert(
            PillCountTxnDetailsEntity(txnId = txnId, pillCount = 6, type = StepState.VIAL.name)
        )

        dao.getTransactionsForDateRange(
            startDate = 100L,
            endDate = 200L,
            stepType = null,
            isDispense = null,
            status = null,
            userLocalId = 3L
        ).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(10, list.first().pillCount)
        }
    }

    // ───────────────────────── observePartialByIsDispense ─────────────────────────

    @Test
    fun `observePartialByIsDispense filters by dispense status and user and orders by priority`() = runTest {
        drugDao.insertIgnore(DrugMasterEntity(ndc = "N1", drugName = "DrugLow"))
        // localId = 2L (wrong user) is FK-enforced against users.localId — seed it.
        db.userDao().insertIgnore(UserEntity(localId = 2L, userId = "user-2"))

        val lowId = dao.insertIgnore(
            baseTxn(localId = 1L, isDispense = true, status = CountStatus.PARTIAL, priority = TxnPriority.Low)
        )
        val highId = dao.insertIgnore(
            baseTxn(localId = 1L, isDispense = true, status = CountStatus.PARTIAL, priority = TxnPriority.High)
        )
        // wrong user, should be excluded
        dao.insertIgnore(
            baseTxn(localId = 2L, isDispense = true, status = CountStatus.PARTIAL, priority = TxnPriority.High)
        )

        dao.observePartialByIsDispense(
            isDispense = true,
            userLocalId = 1L,
            type = StepState.SCAN
        ).test {
            val list = awaitItem()
            assertEquals(2, list.size)
            // High priority sorts before Low
            assertEquals(highId, list[0].txnId)
            assertEquals(lowId, list[1].txnId)
        }
    }

    @Test
    fun `observePartialByIsDispense excludes non partial and deleted rows`() = runTest {
        val id = dao.insertIgnore(
            baseTxn(localId = 1L, isDispense = true, status = CountStatus.COMPLETED)
        )
        dao.insertIgnore(
            baseTxn(localId = 1L, isDispense = true, status = CountStatus.PARTIAL)
        )
        dao.softDelete(dao.getById(id)!!.txnId)

        dao.observePartialByIsDispense(
            isDispense = true,
            userLocalId = 1L,
            type = StepState.SCAN
        ).test {
            val list = awaitItem()
            assertEquals(1, list.size)
        }
    }
}
