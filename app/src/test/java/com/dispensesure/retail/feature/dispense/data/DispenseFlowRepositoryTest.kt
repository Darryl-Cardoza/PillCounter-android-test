package com.dispensesure.retail.feature.dispense.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.UserEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottleJson
import com.dispensesure.retail.feature.dispense.domain.model.StandaloneRxDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DispenseFlowRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DispenseFlowRepository

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = DispenseFlowRepository(db.dispenseTxnDao(), db.drugMasterDao())
        db.userDao().insertIgnore(UserEntity(localId = 1L, userId = "user-1"))
        db.userDao().insertIgnore(UserEntity(localId = 2L, userId = "user-2"))
        db.drugMasterDao().insertIgnore(
            DrugMasterEntity(
                drugId = 10L, drugName = "Aspirin", ndc = "NDC123", drugType = "CII",
                isHazardous = true, drugImagePath = "/images/aspirin.webp",
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertTxn(
        userLocalId: Long? = 1L,
        status: CountStatus = CountStatus.PARTIAL,
        bottles: List<DispenseBottle> = emptyList(),
        priority: TxnPriority? = null,
        isGlovesPresent: Boolean = false,
        rxNo: String? = null,
        refillNo: String? = null,
    ): Long = db.dispenseTxnDao().insertIgnore(
        DispenseTxnEntity(
            userLocalId = userLocalId,
            drugId = 10L,
            status = status,
            targetCount = 30,
            bottlesJson = bottles.takeIf { it.isNotEmpty() }?.let { DispenseBottleJson.encode(it) },
            priority = priority,
            isGlovesPresent = isGlovesPresent,
            rxNo = rxNo,
            refillNo = refillNo,
        )
    )

    private suspend fun savedTxn(txnId: Long) = db.dispenseTxnDao().getById(txnId)!!

    private fun draft(refillNo: String? = null) = StandaloneRxDraft(
        drugId = 10L, rxNo = "RX999", refillNo = refillNo, bucket = "B1", targetCount = 10,
    )

    // ───────────────────────── loads ─────────────────────────

    @Test
    fun `loadDrug with no drug id is null`() = runTest {
        assertNull(repository.loadDrug(null))
        assertEquals("Aspirin", repository.loadDrug(10L)?.drugName)
    }

    @Test
    fun `loadOrderedDrug gives the order's drug, or null for a missing txn`() = runTest {
        val txnId = insertTxn()

        assertEquals("Aspirin", repository.loadOrderedDrug(txnId)?.drugName)
        assertNull(repository.loadOrderedDrug(999L))
    }

    @Test
    fun `clearGlovesPresent clears the gloves flag`() = runTest {
        val txnId = insertTxn(isGlovesPresent = true)

        repository.clearGlovesPresent(txnId)

        assertFalse(savedTxn(txnId).isGlovesPresent)
    }

    // ───────────────────────── Rx label lookups ─────────────────────────

    @Test
    fun `loadTxnByRxNoAndFill finds that fill only`() = runTest {
        insertTxn(rxNo = "RX999", refillNo = "0")
        val secondFillId = insertTxn(rxNo = "RX999", refillNo = "1")

        assertEquals(secondFillId, repository.loadTxnByRxNoAndFill("RX999", "1")?.txnId)
        assertNull(repository.loadTxnByRxNoAndFill("RX999", "2"))
    }

    @Test
    fun `loadMostRecentTxnByRxNo finds the Rx's txn, or null for an unknown Rx`() = runTest {
        val txnId = insertTxn(status = CountStatus.COMPLETED, rxNo = "RX999")

        assertEquals(txnId, repository.loadMostRecentTxnByRxNo("RX999")?.txnId)
        assertNull(repository.loadMostRecentTxnByRxNo("RX000"))
    }

    @Test
    fun `loadActiveTxnByRxNo skips a completed txn`() = runTest {
        insertTxn(status = CountStatus.COMPLETED, rxNo = "RX999")
        assertNull(repository.loadActiveTxnByRxNo("RX999"))

        val partialTxnId = insertTxn(status = CountStatus.PARTIAL, rxNo = "RX999")
        assertEquals(partialTxnId, repository.loadActiveTxnByRxNo("RX999")?.txnId)
    }

    // ───────────────────────── standalone txn ─────────────────────────

    @Test
    fun `createStandaloneTxn saves a PARTIAL, unsynced, unverified txn from the label`() = runTest {
        val txnId = repository.createStandaloneTxn(draft(), userLocalId = 1L)

        val createdTxn = savedTxn(txnId)
        assertEquals(1L, createdTxn.userLocalId)
        assertEquals(10L, createdTxn.drugId)
        assertEquals(10, createdTxn.targetCount)
        assertEquals(CountStatus.PARTIAL, createdTxn.status)
        assertEquals(false, createdTxn.isComingFromHL7)
        assertEquals(false, createdTxn.isSynced)
        assertEquals(false, createdTxn.isNdcVerified)
        assertEquals("RX999", createdTxn.rxNo)
        assertNull(createdTxn.refillNo)
        assertEquals("B1", createdTxn.bucketId)
        assertEquals("RX999", createdTxn.transactionOrderId)
    }

    @Test
    fun `a label with a refill number gets the RxNo-RefillNo order id`() = runTest {
        // PMS pulls dispense images by transactionOrderId, so a label with a fill number
        // must produce the same "RxNo-RefillNo" composite DispenseMessageBuilder sends.
        val txnId = repository.createStandaloneTxn(draft(refillNo = "1"), userLocalId = 1L)

        assertEquals("1", savedTxn(txnId).refillNo)
        assertEquals("RX999-1", savedTxn(txnId).transactionOrderId)
    }

    @Test
    fun `a blank refill number keeps the bare Rx as the order id`() = runTest {
        val txnId = repository.createStandaloneTxn(draft(refillNo = " "), userLocalId = 1L)

        assertEquals("RX999", savedTxn(txnId).transactionOrderId)
    }

    // ───────────────────────── container verified ─────────────────────────

    @Test
    fun `markContainerVerified stamps the first bottle when none is on file`() = runTest {
        val txnId = insertTxn()
        val firstBottle = DispenseBottle(lotNumber = "LOT1", expirationDate = "01-31-2030", serialNumber = "SN1")

        assertTrue(repository.markContainerVerified(txnId, isSubstitute = false, substituteDrug = null, firstBottle = firstBottle))

        val verifiedTxn = savedTxn(txnId)
        assertEquals(true, verifiedTxn.isNdcVerified)
        assertFalse(verifiedTxn.isSubstitute)
        assertNull(verifiedTxn.substitutedDrugId)
        assertEquals(listOf(firstBottle.copy(txnId = txnId)), DispenseBottleJson.decode(verifiedTxn.bottlesJson))
    }

    @Test
    fun `markContainerVerified keeps bottles already on file`() = runTest {
        // Resuming an in-progress dispense must not clobber bottles tracked by a rescan.
        val txnId = insertTxn(bottles = listOf(DispenseBottle(lotNumber = "OLD")))

        repository.markContainerVerified(txnId, isSubstitute = false, substituteDrug = null, firstBottle = DispenseBottle(lotNumber = "NEW"))

        assertEquals(listOf("OLD"), DispenseBottleJson.decode(savedTxn(txnId).bottlesJson).map { it.lotNumber })
    }

    @Test
    fun `markContainerVerified saves the substitute and keeps its downloaded image`() = runTest {
        db.drugMasterDao().insertIgnore(
            DrugMasterEntity(drugId = 20L, drugName = "Old name", ndc = "SUBNDC", drugImagePath = "/cached/sub.webp")
        )
        val txnId = insertTxn()
        val substituteDrug = DrugMasterEntity(ndc = "SUBNDC", drugName = "Generic", drugType = "X", packageQty = 7)

        repository.markContainerVerified(txnId, isSubstitute = true, substituteDrug = substituteDrug, firstBottle = null)

        val verifiedTxn = savedTxn(txnId)
        assertTrue(verifiedTxn.isSubstitute)
        assertEquals(20L, verifiedTxn.substitutedDrugId)
        val savedSubstitute = db.drugMasterDao().getDrugById(20L)!!
        assertEquals("Generic", savedSubstitute.drugName)
        assertEquals(7, savedSubstitute.packageQty)
        assertEquals("/cached/sub.webp", savedSubstitute.drugImagePath)
    }

    @Test
    fun `a substitute flag without a scanned drug links nothing`() = runTest {
        val txnId = insertTxn()

        repository.markContainerVerified(txnId, isSubstitute = true, substituteDrug = null, firstBottle = null)

        assertTrue(savedTxn(txnId).isSubstitute)
        assertNull(savedTxn(txnId).substitutedDrugId)
    }

    @Test
    fun `markContainerVerified on a missing txn is false and writes nothing`() = runTest {
        val substituteDrug = DrugMasterEntity(ndc = "SUBNDC", drugName = "Generic")

        assertFalse(repository.markContainerVerified(999L, isSubstitute = true, substituteDrug = substituteDrug, firstBottle = null))
        assertNull(db.drugMasterDao().getDrugByNdc("SUBNDC"))
    }

    // ───────────────────────── queue ─────────────────────────

    @Test
    fun `pending dispenses are this user's PARTIAL ones only`() = runTest {
        assertFalse(repository.hasPendingDispenses(userLocalId = 1L))

        insertTxn(userLocalId = 1L, status = CountStatus.COMPLETED)
        insertTxn(userLocalId = 2L, status = CountStatus.PARTIAL)
        assertFalse(repository.hasPendingDispenses(userLocalId = 1L))

        insertTxn(userLocalId = 1L, status = CountStatus.PARTIAL)
        assertTrue(repository.hasPendingDispenses(userLocalId = 1L))
    }

    @Test
    fun `the queue lists this user's PARTIAL dispenses with their flags`() = runTest {
        val queuedTxnId = insertTxn(userLocalId = 1L, priority = TxnPriority.High)
        insertTxn(userLocalId = 1L, status = CountStatus.COMPLETED)
        insertTxn(userLocalId = 2L)

        val queueItems = repository.observeQueue(userLocalId = 1L).first()

        val queueItem = queueItems.single()
        assertEquals(queuedTxnId, queueItem.txn.txnId)
        assertTrue(queueItem.isHazardous)
        assertTrue(queueItem.isHighPriority)
        // CII is a controlled schedule.
        assertTrue(queueItem.isControlled)
    }
}
