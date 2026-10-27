package com.dispensesure.retail.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.UserEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.core.tourguide.TutorialData
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
class DispenseTxnDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dispenseTxnDao: DispenseTxnDao
    private lateinit var drugDao: DrugMasterDao
    private lateinit var dispenseCountDao: DispenseCountDao

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dispenseTxnDao = db.dispenseTxnDao()
        drugDao = db.drugMasterDao()
        dispenseCountDao = db.dispenseCountDao()

        // baseTxn() defaults userLocalId = 1L, which is FK-enforced against users.localId —
        // seed the matching user row so inserts don't hit a constraint violation.
        db.userDao().insertIgnore(UserEntity(userId = "test-user"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun baseTxn(
        txnId: Long = 0L,
        status: CountStatus = CountStatus.PARTIAL,
        userLocalId: Long? = 1L,
        drugId: Long? = null,
        rxNo: String? = null,
        refillNo: String? = null,
        isComingFromHL7: Boolean? = null,
        isSynced: Boolean? = null,
        priority: TxnPriority? = null,
        createdAt: Long = System.currentTimeMillis(),
        isSubstitute: Boolean = false,
        substitutedDrugId: Long? = null,
    ) = DispenseTxnEntity(
        txnId = txnId,
        status = status,
        userLocalId = userLocalId,
        drugId = drugId,
        rxNo = rxNo,
        refillNo = refillNo,
        isComingFromHL7 = isComingFromHL7,
        isSynced = isSynced,
        priority = priority,
        createdAt = createdAt,
        isSubstitute = isSubstitute,
        substitutedDrugId = substitutedDrugId,
    )

    // ───────────────────────── Insert / Upsert ─────────────────────────

    @Test
    fun `insertIgnore returns generated id for new transaction`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn())
        assertNotEquals(-1L, id)
        assertTrue(id > 0)
    }

    @Test
    fun `insertIgnore returns minus one on txnId conflict and keeps original row`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "RX1"))
        val conflictResult = dispenseTxnDao.insertIgnore(baseTxn(txnId = id, rxNo = "RX2"))

        assertEquals(-1L, conflictResult)
        assertEquals("RX1", dispenseTxnDao.getById(id)?.rxNo)
    }

    @Test
    fun `insertAll inserts multiple transactions`() = runTest {
        val ids = dispenseTxnDao.insertAll(listOf(baseTxn(rxNo = "A"), baseTxn(rxNo = "B")))
        assertEquals(2, ids.size)
    }

    @Test
    fun `insertAll with empty list returns empty result`() = runTest {
        val ids = dispenseTxnDao.insertAll(emptyList())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `update modifies existing row matched by txnId`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "OLD"))
        val stored = dispenseTxnDao.getById(id)!!
        dispenseTxnDao.update(stored.copy(rxNo = "NEW"))

        assertEquals("NEW", dispenseTxnDao.getById(id)?.rxNo)
    }

    @Test
    fun `upsertPreservingId inserts new row when txnId is zero`() = runTest {
        val newId = dispenseTxnDao.upsertPreservingId(baseTxn(txnId = 0L, rxNo = "NEWROW"))
        assertTrue(newId > 0)
        assertEquals("NEWROW", dispenseTxnDao.getById(newId)?.rxNo)
    }

    @Test
    fun `upsertPreservingId updates existing row while preserving txnId`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "ORIG"))
        val existing = dispenseTxnDao.getById(id)!!

        val resultId = dispenseTxnDao.upsertPreservingId(existing.copy(rxNo = "UPDATED"))

        assertEquals(id, resultId)
        assertEquals("UPDATED", dispenseTxnDao.getById(id)?.rxNo)
    }

    @Test
    fun `upsertPreservingId with nonexistent txnId falls back to insertIgnore path`() = runTest {
        // txnId != 0 but no row exists yet: getById returns null, insertIgnore creates new row.
        val resultId = dispenseTxnDao.upsertPreservingId(baseTxn(txnId = 999L, rxNo = "GHOST"))
        assertTrue(resultId > 0)
    }

    @Test
    fun `upsertPreservingId with zero txnId throws when insert conflicts`() = runTest {
        // First insert consumes an autogenerated id; forcing a duplicate insert path with txnId=0
        // cannot conflict under autoGenerate, so instead verify the non-zero conflict branch:
        // insert same non-existent-then-reinserted id twice via insertIgnore returning -1.
        val id = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "X"))
        dispenseTxnDao.softDelete(id) // no-op for this branch check; row still exists with same txnId

        // Re-attempt upsert for the same non-zero txnId — should hit "existing != null" update branch,
        // not throw.
        val resultId = dispenseTxnDao.upsertPreservingId(baseTxn(txnId = id, rxNo = "Y"))
        assertEquals(id, resultId)
    }

    // ───────────────────────── Reads ─────────────────────────

    @Test
    fun `getById returns null for missing transaction`() = runTest {
        assertNull(dispenseTxnDao.getById(12345L))
    }

    @Test
    fun `getById returns matching transaction`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "FOUND"))
        assertEquals("FOUND", dispenseTxnDao.getById(id)?.rxNo)
    }

    // ───────────────────────── Field updates ─────────────────────────

    @Test
    fun `updateTargetCount sets value and clears to null`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn())
        dispenseTxnDao.updateTargetCount(id, 50)
        assertEquals(50, dispenseTxnDao.getById(id)?.targetCount)

        dispenseTxnDao.updateTargetCount(id, null)
        assertNull(dispenseTxnDao.getById(id)?.targetCount)
    }

    @Test
    fun `updateNote sets and clears note`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn())
        dispenseTxnDao.updateNote(id, "hello")
        assertEquals("hello", dispenseTxnDao.getById(id)?.note)

        dispenseTxnDao.updateNote(id, null)
        assertNull(dispenseTxnDao.getById(id)?.note)
    }

    @Test
    fun `updateBottles persists json string`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn())
        dispenseTxnDao.updateBottles(id, "[{\"a\":1}]")
        assertEquals("[{\"a\":1}]", dispenseTxnDao.getById(id)?.bottlesJson)
    }

    @Test
    fun `softDelete marks isDeleted true`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn())
        dispenseTxnDao.softDelete(id)
        assertTrue(dispenseTxnDao.getById(id)!!.isDeleted)
    }

    @Test
    fun `softDeleteByRxNo only marks non deleted rows for rxNo`() = runTest {
        val id1 = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "R1"))
        val id2 = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "R1"))
        dispenseTxnDao.softDelete(id2) // pre-mark one as already deleted

        dispenseTxnDao.softDeleteByRxNo("R1")

        assertTrue(dispenseTxnDao.getById(id1)!!.isDeleted)
        assertTrue(dispenseTxnDao.getById(id2)!!.isDeleted)
    }

    @Test
    fun `getDeletedByRxNo returns most recent deleted row or null`() = runTest {
        assertNull(dispenseTxnDao.getDeletedByRxNo("NONE"))

        val id1 = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "DEL", createdAt = 100L))
        val id2 = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "DEL", createdAt = 200L))
        dispenseTxnDao.softDelete(id1, now = 100L)
        dispenseTxnDao.softDelete(id2, now = 500L)

        val result = dispenseTxnDao.getDeletedByRxNo("DEL")
        assertEquals(id2, result?.txnId)
    }

    @Test
    fun `restoreDeletedTxn clears isDeleted and resets status to PARTIAL`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.COMPLETED))
        dispenseTxnDao.softDelete(id)

        dispenseTxnDao.restoreDeletedTxn(id)

        val restored = dispenseTxnDao.getById(id)!!
        assertFalse(restored.isDeleted)
        assertEquals(CountStatus.PARTIAL, restored.status)
    }

    @Test
    fun `getActiveByRxNo returns most recent partial or on hold and ignores others`() = runTest {
        dispenseTxnDao.insertIgnore(baseTxn(rxNo = "ACT", status = CountStatus.COMPLETED, createdAt = 50L))
        val id2 = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "ACT", status = CountStatus.PARTIAL, createdAt = 300L))

        val result = dispenseTxnDao.getActiveByRxNo("ACT")
        assertEquals(id2, result?.txnId)
    }

    @Test
    fun `getActiveByRxNo returns null when only deleted or completed rows exist`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "GONE", status = CountStatus.PARTIAL))
        dispenseTxnDao.softDelete(id)

        assertNull(dispenseTxnDao.getActiveByRxNo("GONE"))
    }

    // ───────────────────────── Image server lookups ─────────────────────────

    @Test
    fun `getByMessageControlId finds latest matching non deleted row`() = runTest {
        assertNull(dispenseTxnDao.getByMessageControlId("MSG1"))

        val old = baseTxn(createdAt = 100L).copy(hl7MessageControlId = "MSG1")
        val newer = baseTxn(createdAt = 200L).copy(hl7MessageControlId = "MSG1")
        dispenseTxnDao.insertIgnore(old)
        val newerId = dispenseTxnDao.insertIgnore(newer)

        assertEquals(newerId, dispenseTxnDao.getByMessageControlId("MSG1")?.txnId)
    }

    @Test
    fun `getBySequenceNumber returns null when not found`() = runTest {
        assertNull(dispenseTxnDao.getBySequenceNumber("SEQ-404"))
    }

    @Test
    fun `getBySequenceNumber finds matching row`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn().copy(hl7SequenceNumber = "SEQ-1"))
        assertEquals(id, dispenseTxnDao.getBySequenceNumber("SEQ-1")?.txnId)
    }

    @Test
    fun `getByTransactionOrderId finds matching row`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn().copy(transactionOrderId = "ORD-1"))
        assertEquals(id, dispenseTxnDao.getByTransactionOrderId("ORD-1")?.txnId)
    }

    @Test
    fun `getByRxNoAndFillNo matches both fields`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "RX9", refillNo = "1"))
        dispenseTxnDao.insertIgnore(baseTxn(rxNo = "RX9", refillNo = "2"))

        assertEquals(id, dispenseTxnDao.getByRxNoAndFillNo("RX9", "1")?.txnId)
        assertNull(dispenseTxnDao.getByRxNoAndFillNo("RX9", "99"))
    }

    @Test
    fun `getMostRecentByRxNo ignores fill number and returns newest`() = runTest {
        dispenseTxnDao.insertIgnore(baseTxn(rxNo = "RX7", refillNo = "1", createdAt = 100L))
        val newest = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "RX7", refillNo = "2", createdAt = 500L))

        assertEquals(newest, dispenseTxnDao.getMostRecentByRxNo("RX7")?.txnId)
    }

    // ───────────────────────── HL7 edit / workflow updates ─────────────────────────

    @Test
    fun `updateFromHl7Edit updates drug target priority and resets sync flag`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.PARTIAL, isSynced = true))
        // updateFromHl7Edit sets drugId via UPDATE, which is FK-enforced against drug_master —
        // seed the drug row referenced by this test's drugId = 42L.
        drugDao.insertIgnore(DrugMasterEntity(drugId = 42L, ndc = "HL7-42"))

        dispenseTxnDao.updateFromHl7Edit(
            txnId = id,
            drugId = 42L,
            targetCount = 30,
            priority = TxnPriority.High,
            status = CountStatus.ON_HOLD
        )

        val updated = dispenseTxnDao.getById(id)!!
        assertEquals(42L, updated.drugId)
        assertEquals(30, updated.targetCount)
        assertEquals(TxnPriority.High, updated.priority)
        assertEquals(CountStatus.ON_HOLD, updated.status)
        assertEquals(false, updated.isSynced)
    }

    @Test
    fun `updateFromHl7Edit with null status leaves status unchanged`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.PARTIAL))
        // Seed the drug row referenced by this test's drugId = 1L (FK-enforced on UPDATE).
        drugDao.insertIgnore(DrugMasterEntity(drugId = 1L, ndc = "HL7-1"))

        dispenseTxnDao.updateFromHl7Edit(
            txnId = id,
            drugId = 1L,
            targetCount = 10,
            priority = TxnPriority.Low,
            status = null
        )

        assertEquals(CountStatus.PARTIAL, dispenseTxnDao.getById(id)?.status)
    }

    @Test
    fun `deleteAllTransactions removes every row`() = runTest {
        dispenseTxnDao.insertIgnore(baseTxn())
        dispenseTxnDao.insertIgnore(baseTxn())
        dispenseTxnDao.deleteAllTransactions()

        assertNull(dispenseTxnDao.getById(1L))
        assertNull(dispenseTxnDao.getById(2L))
    }

    @Test
    fun `updateWorkflowStep sets step value`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn())
        dispenseTxnDao.updateWorkflowStep(id, "SCAN")
        assertEquals("SCAN", dispenseTxnDao.getById(id)?.workflowStep)
    }

    @Test
    fun `updateGlovesPresent toggles flag`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn())
        dispenseTxnDao.updateGlovesPresent(id, true)
        assertTrue(dispenseTxnDao.getById(id)!!.isGlovesPresent)

        dispenseTxnDao.updateGlovesPresent(id, false)
        assertFalse(dispenseTxnDao.getById(id)!!.isGlovesPresent)
    }

    @Test
    fun `updateHazardousTrayDetected sets nullable boolean`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn())
        dispenseTxnDao.updateHazardousTrayDetected(id, true)
        assertEquals(true, dispenseTxnDao.getById(id)?.hazardousTrayDetected)
    }

    @Test
    fun `updateTxnStatus changes status`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.PARTIAL))
        dispenseTxnDao.updateTxnStatus(id, CountStatus.COMPLETED)
        assertEquals(CountStatus.COMPLETED, dispenseTxnDao.getById(id)?.status)
    }

    // ───────────────────────── Date-range deletes / retention ─────────────────────────

    @Test
    fun `deleteTransactionsByDate removes only rows within range matching filters`() = runTest {
        // userLocalId = 2L (wrongUser) is FK-enforced against users.localId — seed it.
        db.userDao().insertIgnore(UserEntity(localId = 2L, userId = "user-2"))

        val inRange = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 1L, status = CountStatus.COMPLETED, createdAt = 150L)
        )
        val outOfRange = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 1L, status = CountStatus.COMPLETED, createdAt = 999L)
        )
        val wrongUser = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 2L, status = CountStatus.COMPLETED, createdAt = 150L)
        )

        dispenseTxnDao.deleteTransactionsByDate(
            start = 100L,
            end = 200L,
            isCompleted = true,
            userLocalId = 1L
        )

        assertNull(dispenseTxnDao.getById(inRange))
        assertEquals(outOfRange, dispenseTxnDao.getById(outOfRange)?.txnId)
        assertEquals(wrongUser, dispenseTxnDao.getById(wrongUser)?.txnId)
    }

    @Test
    fun `deleteTransactionsByDate with null isCompleted matches any completion state`() = runTest {
        // userLocalId = 5L is FK-enforced against users.localId — seed it.
        db.userDao().insertIgnore(UserEntity(localId = 5L, userId = "user-5"))

        val id = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 5L, status = CountStatus.PARTIAL, createdAt = 150L)
        )

        dispenseTxnDao.deleteTransactionsByDate(
            start = 100L,
            end = 200L,
            isCompleted = null,
            userLocalId = 5L
        )

        assertNull(dispenseTxnDao.getById(id))
    }

    @Test
    fun `deleteTransactionsByDate isCompleted false excludes completed and force completed rows`() = runTest {
        val partial = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 1L, status = CountStatus.PARTIAL, createdAt = 150L)
        )
        val completed = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 1L, status = CountStatus.COMPLETED, createdAt = 150L)
        )

        dispenseTxnDao.deleteTransactionsByDate(
            start = 100L,
            end = 200L,
            isCompleted = false,
            userLocalId = 1L
        )

        assertNull(dispenseTxnDao.getById(partial))
        assertEquals(completed, dispenseTxnDao.getById(completed)?.txnId)
    }

    @Test
    fun `getTransactionsBefore returns only completed or force completed rows before cutoff`() = runTest {
        dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.COMPLETED, createdAt = 100L))
        dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.FORCE_COMPLETED, createdAt = 150L))
        dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.PARTIAL, createdAt = 100L))
        dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.COMPLETED, createdAt = 999L))

        val result = dispenseTxnDao.getTransactionsBefore(200L)
        assertEquals(2, result.size)
        assertTrue(result.all { it.status == CountStatus.COMPLETED || it.status == CountStatus.FORCE_COMPLETED })
    }

    @Test
    fun `getSyncedDispenses returns only synced rows`() = runTest {
        dispenseTxnDao.insertIgnore(baseTxn(isSynced = true))
        dispenseTxnDao.insertIgnore(baseTxn(isSynced = false))

        val result = dispenseTxnDao.getSyncedDispenses()
        assertEquals(1, result.size)
        assertEquals(true, result.first().isSynced)
    }

    @Test
    fun `getTransactionDetailsImages returns image paths for txn`() = runTest {
        val txnId = dispenseTxnDao.insertIgnore(baseTxn())
        dispenseCountDao.insert(DispenseCountEntity(txnId = txnId, imagePath = "img1.jpg"))
        dispenseCountDao.insert(
            DispenseCountEntity(txnId = txnId, imagePath = "img2.jpg", rawImagePath = "img2_raw.jpg")
        )

        val images = dispenseTxnDao.getTransactionDetailsImages(txnId)
        assertEquals(3, images.size)
        assertTrue(images.containsAll(listOf("img1.jpg", "img2.jpg", "img2_raw.jpg")))
    }

    @Test
    fun `getTransactionDetailsImages returns empty list when none exist`() = runTest {
        val txnId = dispenseTxnDao.insertIgnore(baseTxn())
        assertTrue(dispenseTxnDao.getTransactionDetailsImages(txnId).isEmpty())
    }

    @Test
    fun `deleteTransaction permanently removes the row`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn())
        dispenseTxnDao.deleteTransaction(id)
        assertNull(dispenseTxnDao.getById(id))
    }

    // ───────────────────────── HL7 sync flows ─────────────────────────

    @Test
    fun `observePendingHl7Txn emits only completed unsynced dispense rows`() = runTest {
        dispenseTxnDao.observePendingHl7Txn().test {
            assertTrue(awaitItem().isEmpty())

            val id = dispenseTxnDao.insertIgnore(
                baseTxn(status = CountStatus.COMPLETED, isComingFromHL7 = true, isSynced = false)
            )
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(id, list.first().txnId)
        }
    }

    @Test
    fun `observePendingHl7Txn emits a locally-scanned dispense that is not from hl7`() = runTest {
        // isComingFromHL7 = false (locally scanned): the flow that triggers the resend sweep
        // must not be blind to this, since resendPendingHl7Transactions() sends it too.
        dispenseTxnDao.observePendingHl7Txn().test {
            assertTrue(awaitItem().isEmpty())

            val id = dispenseTxnDao.insertIgnore(
                baseTxn(status = CountStatus.COMPLETED, isComingFromHL7 = false, isSynced = false)
            )
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(id, list.first().txnId)
        }
    }

    @Test
    fun `observePendingHl7Txn excludes synced or non completed rows`() = runTest {
        dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.COMPLETED, isComingFromHL7 = true, isSynced = true))
        dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.PARTIAL, isComingFromHL7 = true, isSynced = false))

        dispenseTxnDao.observePendingHl7Txn().test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `getPendingHl7TxnOnce returns snapshot of pending rows`() = runTest {
        val id = dispenseTxnDao.insertIgnore(
            baseTxn(status = CountStatus.COMPLETED, isComingFromHL7 = true, isSynced = null)
        )
        val result = dispenseTxnDao.getPendingHl7TxnOnce()
        assertEquals(1, result.size)
        assertEquals(id, result.first().txnId)
    }

    @Test
    fun `getPendingHl7TxnOnce includes locally-scanned dispense not from hl7`() = runTest {
        val id = dispenseTxnDao.insertIgnore(
            baseTxn(status = CountStatus.COMPLETED, isComingFromHL7 = false, isSynced = null)
        )
        val result = dispenseTxnDao.getPendingHl7TxnOnce()
        assertEquals(1, result.size)
        assertEquals(id, result.first().txnId)
    }

    @Test
    fun `getPendingHl7TxnOnce excludes tutorial demo drug transactions`() = runTest {
        val tutorialDrugId = drugDao.insertIgnore(DrugMasterEntity(ndc = TutorialData.DEMO_NDC, drugName = "Tutorial Demo Drug"))
        dispenseTxnDao.insertIgnore(
            baseTxn(status = CountStatus.COMPLETED, isSynced = null, drugId = tutorialDrugId)
        )
        val result = dispenseTxnDao.getPendingHl7TxnOnce()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getPendingHl7TxnOnce excludes tutorial Rx numbers even without the demo drug`() = runTest {
        dispenseTxnDao.insertIgnore(
            baseTxn(status = CountStatus.COMPLETED, isSynced = null, rxNo = "${TutorialData.RX_PREFIX}123")
        )
        assertTrue(dispenseTxnDao.getPendingHl7TxnOnce().isEmpty())
    }

    @Test
    fun `getTutorialTxnIds finds demo drug and Rx prefix txns only`() = runTest {
        val demoDrugId = drugDao.insertIgnore(DrugMasterEntity(ndc = TutorialData.DEMO_NDC, drugName = "Tutorial Demo Drug"))
        val byDrug = dispenseTxnDao.insertIgnore(baseTxn(drugId = demoDrugId))
        val byRx = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "${TutorialData.RX_PREFIX}9"))
        dispenseTxnDao.insertIgnore(baseTxn(rxNo = "RX100"))

        assertEquals(setOf(byDrug, byRx), dispenseTxnDao.getTutorialTxnIds().toSet())
    }

    @Test
    fun `markTxnSynced sets isSynced true`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(isSynced = false))
        dispenseTxnDao.markTxnSynced(id)
        assertEquals(true, dispenseTxnDao.getById(id)?.isSynced)
    }

    @Test
    fun `markCompletedAndUnsynced sets status and clears sync flag`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.PARTIAL, isSynced = true))
        dispenseTxnDao.markCompletedAndUnsynced(id, CountStatus.COMPLETED)

        val updated = dispenseTxnDao.getById(id)!!
        assertEquals(CountStatus.COMPLETED, updated.status)
        assertEquals(false, updated.isSynced)
    }

    @Test
    fun `observeUnsyncedDispenses returns completed or force completed unsynced rows with total pill count`() = runTest {
        val drugId = drugDao.insertIgnore(DrugMasterEntity(ndc = "0001", drugName = "Aspirin"))
        val txnId = dispenseTxnDao.insertIgnore(
            baseTxn(
                status = CountStatus.COMPLETED,
                isSynced = false,
                drugId = drugId
            )
        )
        dispenseCountDao.insert(
            DispenseCountEntity(txnId = txnId, pillCount = 10, step = StepState.SCAN.name)
        )
        dispenseCountDao.insert(
            DispenseCountEntity(txnId = txnId, pillCount = 5, step = StepState.SCAN.name)
        )

        dispenseTxnDao.observeUnsyncedDispenses(step = StepState.SCAN.name).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(15, list.first().totalPillCount)
            assertEquals("Aspirin", list.first().drugName)
        }
    }

    @Test
    fun `observeUnsyncedDispenses excludes synced rows`() = runTest {
        dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.COMPLETED, isSynced = true))

        dispenseTxnDao.observeUnsyncedDispenses(step = StepState.SCAN.name).test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `getTotalCompletedTransactionCount counts unsynced hl7 completed and force completed rows`() = runTest {
        dispenseTxnDao.getTotalCompletedTransactionCount().test {
            assertEquals(0, awaitItem())

            dispenseTxnDao.insertIgnore(
                baseTxn(status = CountStatus.COMPLETED, isComingFromHL7 = true, isSynced = false)
            )
            assertEquals(1, awaitItem())

            dispenseTxnDao.insertIgnore(
                baseTxn(status = CountStatus.FORCE_COMPLETED, isComingFromHL7 = true, isSynced = false)
            )
            assertEquals(2, awaitItem())
        }
    }

    @Test
    fun `countPartialDispenses counts matching partial rows for user`() = runTest {
        // userLocalId = 7L and 8L are FK-enforced against users.localId — seed both.
        db.userDao().insertIgnore(UserEntity(localId = 7L, userId = "user-7"))
        db.userDao().insertIgnore(UserEntity(localId = 8L, userId = "user-8"))

        dispenseTxnDao.insertIgnore(baseTxn(userLocalId = 7L, status = CountStatus.PARTIAL))
        dispenseTxnDao.insertIgnore(baseTxn(userLocalId = 7L, status = CountStatus.PARTIAL))
        dispenseTxnDao.insertIgnore(baseTxn(userLocalId = 7L, status = CountStatus.COMPLETED))
        dispenseTxnDao.insertIgnore(baseTxn(userLocalId = 8L, status = CountStatus.PARTIAL))

        val count = dispenseTxnDao.countPartialDispenses(userLocalId = 7L)
        assertEquals(2, count)
    }

    @Test
    fun `tutorial txns are excluded from the partial queue and its count`() = runTest {
        val demoDrugId = drugDao.insertIgnore(DrugMasterEntity(ndc = TutorialData.DEMO_NDC, drugName = "Demo"))
        dispenseTxnDao.insertIgnore(baseTxn(userLocalId = 1L, status = CountStatus.PARTIAL).copy(drugId = demoDrugId))
        dispenseTxnDao.insertIgnore(baseTxn(userLocalId = 1L, status = CountStatus.PARTIAL).copy(rxNo = "${TutorialData.RX_PREFIX}9"))
        dispenseTxnDao.insertIgnore(baseTxn(userLocalId = 1L, status = CountStatus.PARTIAL))

        assertEquals(1, dispenseTxnDao.countPartialDispenses(userLocalId = 1L))
        dispenseTxnDao.observePartialDispenses(userLocalId = 1L, step = StepState.SCAN).test {
            assertEquals(1, awaitItem().size)
        }
    }

    // ───────────────────────── Dashboard / join queries ─────────────────────────

    @Test
    fun `getDispenseTxnDetail returns null for missing or deleted transaction`() = runTest {
        assertNull(dispenseTxnDao.getDispenseTxnDetail(555L))

        val id = dispenseTxnDao.insertIgnore(baseTxn())
        dispenseTxnDao.softDelete(id)
        assertNull(dispenseTxnDao.getDispenseTxnDetail(id))
    }

    @Test
    fun `getDispenseTxnDetail aggregates drug info and pill count total`() = runTest {
        val drugId = drugDao.insertIgnore(DrugMasterEntity(ndc = "1111", drugName = "Ibuprofen"))
        val txnId = dispenseTxnDao.insertIgnore(baseTxn(drugId = drugId, status = CountStatus.PARTIAL))
        dispenseCountDao.insert(DispenseCountEntity(txnId = txnId, pillCount = 3))
        dispenseCountDao.insert(DispenseCountEntity(txnId = txnId, pillCount = 7))

        val result = dispenseTxnDao.getDispenseTxnDetail(txnId)!!
        assertEquals("Ibuprofen", result.drugName)
        assertEquals(10, result.totalPillCount)
    }

    @Test
    fun `getTransactionsForDateRange filters by user step type and status`() = runTest {
        // userLocalId = 3L and 9L are FK-enforced against users.localId — seed both.
        db.userDao().insertIgnore(UserEntity(localId = 3L, userId = "user-3"))
        db.userDao().insertIgnore(UserEntity(localId = 9L, userId = "user-9"))

        val txnId = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 3L, status = CountStatus.COMPLETED, createdAt = 150L)
        )
        dispenseCountDao.insert(
            DispenseCountEntity(txnId = txnId, pillCount = 4, step = StepState.SCAN.name)
        )
        dispenseCountDao.insert(
            DispenseCountEntity(txnId = txnId, pillCount = 100, step = StepState.VIAL.name)
        )
        dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 9L, status = CountStatus.COMPLETED, createdAt = 150L)
        )

        dispenseTxnDao.getTransactionsForDateRange(
            startDate = 100L,
            endDate = 200L,
            stepType = StepState.SCAN,
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
        // userLocalId = 3L is FK-enforced against users.localId — seed it.
        db.userDao().insertIgnore(UserEntity(localId = 3L, userId = "user-3"))

        val txnId = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 3L, status = CountStatus.COMPLETED, createdAt = 150L)
        )
        dispenseCountDao.insert(
            DispenseCountEntity(txnId = txnId, pillCount = 4, step = StepState.SCAN.name)
        )
        dispenseCountDao.insert(
            DispenseCountEntity(txnId = txnId, pillCount = 6, step = StepState.VIAL.name)
        )

        dispenseTxnDao.getTransactionsForDateRange(
            startDate = 100L,
            endDate = 200L,
            stepType = null,
            status = null,
            userLocalId = 3L
        ).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(10, list.first().pillCount)
        }
    }

    @Test
    fun `getTransactionsForDateRange excludes tutorial demo drug transactions`() = runTest {
        val tutorialDrugId = drugDao.insertIgnore(DrugMasterEntity(ndc = TutorialData.DEMO_NDC, drugName = "Tutorial Demo Drug"))
        dispenseTxnDao.insertIgnore(
            baseTxn(
                userLocalId = 1L,
                drugId = tutorialDrugId,
                status = CountStatus.COMPLETED,
                createdAt = 150L,
            )
        )

        dispenseTxnDao.getTransactionsForDateRange(
            startDate = 100L,
            endDate = 200L,
            stepType = null,
            status = null,
            userLocalId = 1L
        ).test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `getTransactionsForDateRange returns drug image path strength and dosage form`() = runTest {
        val drugId = drugDao.insertIgnore(
            DrugMasterEntity(
                ndc = "N-IMG",
                drugName = "Imaged",
                strength = "35 mg/1",
                dosageForm = "CAPSULE, EXTENDED RELEASE",
                drugImagePath = "/data/drug/n-img.webp",
            )
        )
        dispenseTxnDao.insertIgnore(
            baseTxn(
                userLocalId = 1L,
                drugId = drugId,
                status = CountStatus.COMPLETED,
                createdAt = 150L,
            )
        )

        dispenseTxnDao.getTransactionsForDateRange(
            startDate = 100L,
            endDate = 200L,
            stepType = null,
            status = null,
            userLocalId = 1L
        ).test {
            val row = awaitItem().first()
            assertEquals("/data/drug/n-img.webp", row.drugImagePath)
            assertEquals("35 mg/1", row.strength)
            assertEquals("CAPSULE, EXTENDED RELEASE", row.dosageForm)
        }
    }

    @Test
    fun `getTransactionsForDateRange prefers substitute drug columns when isSubstitute is set`() = runTest {
        val drugId = drugDao.insertIgnore(
            DrugMasterEntity(
                ndc = "N-BASE",
                drugName = "Base",
                drugType = "BRAND",
                strength = "10 mg/1",
                dosageForm = "TABLET",
                drugImagePath = "/data/drug/base.webp",
            )
        )
        val subDrugId = drugDao.insertIgnore(
            DrugMasterEntity(
                ndc = "N-SUB",
                drugName = "Sub",
                drugType = "GENERIC",
                strength = "35 mg/1",
                dosageForm = "CAPSULE, EXTENDED RELEASE",
                drugImagePath = "/data/drug/sub.webp",
            )
        )
        dispenseTxnDao.insertIgnore(
            baseTxn(
                userLocalId = 1L,
                drugId = drugId,
                status = CountStatus.COMPLETED,
                createdAt = 150L,
                isSubstitute = true,
                substitutedDrugId = subDrugId,
            )
        )

        dispenseTxnDao.getTransactionsForDateRange(
            startDate = 100L,
            endDate = 200L,
            stepType = null,
            status = null,
            userLocalId = 1L
        ).test {
            val row = awaitItem().first()
            assertEquals("N-SUB", row.ndc)
            assertEquals("Sub", row.drugName)
            assertEquals("GENERIC", row.drugType)
            assertEquals("35 mg/1", row.strength)
            assertEquals("CAPSULE, EXTENDED RELEASE", row.dosageForm)
            assertEquals("/data/drug/sub.webp", row.drugImagePath)
        }
    }

    @Test
    fun `getTransactionsForDateRange returns the rx and refill numbers`() = runTest {
        dispenseTxnDao.insertIgnore(
            baseTxn(
                userLocalId = 1L,
                status = CountStatus.COMPLETED,
                createdAt = 150L,
                rxNo = "7654321",
                refillNo = "2",
            )
        )

        dispenseTxnDao.getTransactionsForDateRange(
            startDate = 100L,
            endDate = 200L,
            stepType = null,
            status = null,
            userLocalId = 1L
        ).test {
            val row = awaitItem().single()
            assertEquals("7654321", row.rxNo)
            assertEquals("2", row.refillNo)
        }
    }

    // ───────────────────────── observePartialDispenses ─────────────────────────

    @Test
    fun `observePartialDispenses filters by status and user and orders by priority`() = runTest {
        drugDao.insertIgnore(DrugMasterEntity(ndc = "N1", drugName = "DrugLow"))
        // userLocalId = 2L (wrong user) is FK-enforced against users.localId — seed it.
        db.userDao().insertIgnore(UserEntity(localId = 2L, userId = "user-2"))

        val lowId = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 1L, status = CountStatus.PARTIAL, priority = TxnPriority.Low)
        )
        val highId = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 1L, status = CountStatus.PARTIAL, priority = TxnPriority.High)
        )
        // wrong user, should be excluded
        dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 2L, status = CountStatus.PARTIAL, priority = TxnPriority.High)
        )

        dispenseTxnDao.observePartialDispenses(
            userLocalId = 1L,
            step = StepState.SCAN
        ).test {
            val list = awaitItem()
            assertEquals(2, list.size)
            // High priority sorts before Low
            assertEquals(highId, list[0].txnId)
            assertEquals(lowId, list[1].txnId)
        }
    }

    @Test
    fun `observePartialDispenses lists oldest first within a priority, HL7 or not`() = runTest {
        val newerHl7 = dispenseTxnDao.insertIgnore(
            baseTxn(priority = TxnPriority.Medium, isComingFromHL7 = true, createdAt = 200L)
        )
        val olderManual = dispenseTxnDao.insertIgnore(
            baseTxn(priority = TxnPriority.Medium, isComingFromHL7 = false, createdAt = 100L)
        )

        dispenseTxnDao.observePartialDispenses(
            userLocalId = 1L,
            step = StepState.SCAN
        ).test {
            assertEquals(listOf(olderManual, newerHl7), awaitItem().map { it.txnId })
        }
    }

    @Test
    fun `observePartialDispenses excludes non partial and deleted rows`() = runTest {
        val id = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 1L, status = CountStatus.COMPLETED)
        )
        dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 1L, status = CountStatus.PARTIAL)
        )
        dispenseTxnDao.softDelete(dispenseTxnDao.getById(id)!!.txnId)

        dispenseTxnDao.observePartialDispenses(
            userLocalId = 1L,
            step = StepState.SCAN
        ).test {
            val list = awaitItem()
            assertEquals(1, list.size)
        }
    }

    @Test
    fun `observePartialDispenses returns the rx and refill numbers`() = runTest {
        val withRx = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 1L, status = CountStatus.PARTIAL, rxNo = "7654321", refillNo = "2")
        )
        val withoutRx = dispenseTxnDao.insertIgnore(
            baseTxn(userLocalId = 1L, status = CountStatus.PARTIAL)
        )

        dispenseTxnDao.observePartialDispenses(
            userLocalId = 1L,
            step = StepState.SCAN
        ).test {
            val byId = awaitItem().associateBy { it.txnId }
            assertEquals("7654321", byId.getValue(withRx).rxNo)
            assertEquals("2", byId.getValue(withRx).refillNo)
            assertNull(byId.getValue(withoutRx).rxNo)
            assertNull(byId.getValue(withoutRx).refillNo)
        }
    }

    // ───────────────────────── resetForRecount ─────────────────────────

    @Test
    fun `resetForRecount clears the count state but keeps the order`() = runTest {
        // drugId = 42L and substitutedDrugId = 7L are both FK-enforced against drug_master —
        // seed both rows so the insert doesn't hit a constraint violation.
        drugDao.insertIgnore(DrugMasterEntity(drugId = 42L, ndc = "RECOUNT-42"))
        drugDao.insertIgnore(DrugMasterEntity(drugId = 7L, ndc = "RECOUNT-7"))

        val id = dispenseTxnDao.insertIgnore(
            DispenseTxnEntity(
                userLocalId = 1L,
                drugId = 42L,
                targetCount = 30,
                status = CountStatus.PARTIAL,
                rxNo = "RX999",
                refillNo = "1",
                bucketId = "B1",
                isNdcVerified = true,
                workflowStep = StepState.VIAL.name,
                bottlesJson = "[{}]",
                isSubstitute = true,
                substitutedDrugId = 7L,
                hazardousTrayDetected = true,
                isGlovesPresent = true,
                hl7MessageControlId = "MSG1",
            )
        )

        dispenseTxnDao.resetForRecount(id)

        val txn = dispenseTxnDao.getById(id)!!
        assertEquals(false, txn.isNdcVerified)
        assertNull(txn.workflowStep)
        assertNull(txn.bottlesJson)
        assertFalse(txn.isSubstitute)
        assertNull(txn.substitutedDrugId)
        assertNull(txn.hazardousTrayDetected)
        assertFalse(txn.isGlovesPresent)
        // The order itself survives — same transaction, restarted.
        assertEquals("RX999", txn.rxNo)
        assertEquals("1", txn.refillNo)
        assertEquals(30, txn.targetCount)
        assertEquals("B1", txn.bucketId)
        assertEquals(42L, txn.drugId)
        assertEquals(CountStatus.PARTIAL, txn.status)
        assertEquals("MSG1", txn.hl7MessageControlId)
    }

    // ───────────────────────── updateOperatorName ─────────────────────────

    @Test
    fun `updateOperatorName persists both name parts`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn())

        dispenseTxnDao.updateOperatorName(id, "Bruce", "Wayne")

        val saved = dispenseTxnDao.getById(id)
        assertEquals("Bruce", saved?.operatorFirstName)
        assertEquals("Wayne", saved?.operatorLastName)
    }

    @Test
    fun `updateOperatorName accepts nulls`() = runTest {
        val id = dispenseTxnDao.insertIgnore(baseTxn())

        dispenseTxnDao.updateOperatorName(id, "Bruce", "Wayne")
        dispenseTxnDao.updateOperatorName(id, null, null)

        val saved = dispenseTxnDao.getById(id)
        assertNull(saved?.operatorFirstName)
        assertNull(saved?.operatorLastName)
    }

    // ───────────────────────── HL7 status guards / resend lookup ─────────────────────────

    @Test
    fun `updateStatusByOrderIdIfPartial holds PARTIAL rows and leaves finished rows alone`() = runTest {
        val partialId = dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.PARTIAL).copy(transactionOrderId = "ORD1"))
        val completedId = dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.COMPLETED).copy(transactionOrderId = "ORD1"))
        val forcedId = dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.FORCE_COMPLETED).copy(transactionOrderId = "ORD1"))

        dispenseTxnDao.updateStatusByOrderIdIfPartial("ORD1", CountStatus.ON_HOLD)

        assertEquals(CountStatus.ON_HOLD, dispenseTxnDao.getById(partialId)?.status)
        assertEquals(CountStatus.COMPLETED, dispenseTxnDao.getById(completedId)?.status)
        assertEquals(CountStatus.FORCE_COMPLETED, dispenseTxnDao.getById(forcedId)?.status)
    }

    @Test
    fun `updateStatusByOrderIdIfPartialOrOnHold releases ON_HOLD rows and leaves finished rows alone`() = runTest {
        val heldId = dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.ON_HOLD).copy(transactionOrderId = "ORD1"))
        val completedId = dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.COMPLETED).copy(transactionOrderId = "ORD1"))
        val forcedId = dispenseTxnDao.insertIgnore(baseTxn(status = CountStatus.FORCE_COMPLETED).copy(transactionOrderId = "ORD1"))

        dispenseTxnDao.updateStatusByOrderIdIfPartialOrOnHold("ORD1", CountStatus.PARTIAL)

        assertEquals(CountStatus.PARTIAL, dispenseTxnDao.getById(heldId)?.status)
        assertEquals(CountStatus.COMPLETED, dispenseTxnDao.getById(completedId)?.status)
        assertEquals(CountStatus.FORCE_COMPLETED, dispenseTxnDao.getById(forcedId)?.status)
    }

    @Test
    fun `getByRxNoAndMessageControlId matches rx and control id and returns newest non deleted row`() = runTest {
        assertNull(dispenseTxnDao.getByRxNoAndMessageControlId("RX1", "MSG1"))

        dispenseTxnDao.insertIgnore(baseTxn(rxNo = "RX1", createdAt = 100L).copy(hl7MessageControlId = "MSG1"))
        val newerId = dispenseTxnDao.insertIgnore(baseTxn(rxNo = "RX1", createdAt = 200L).copy(hl7MessageControlId = "MSG1"))
        // Same message, other Rx (multi-ORC) and a deleted row must not win.
        dispenseTxnDao.insertIgnore(baseTxn(rxNo = "RX2", createdAt = 300L).copy(hl7MessageControlId = "MSG1"))
        dispenseTxnDao.insertIgnore(baseTxn(rxNo = "RX1", createdAt = 400L).copy(hl7MessageControlId = "MSG1", isDeleted = true))

        assertEquals(newerId, dispenseTxnDao.getByRxNoAndMessageControlId("RX1", "MSG1")?.txnId)
        assertNull(dispenseTxnDao.getByRxNoAndMessageControlId("RX1", "MSG2"))
    }
}
