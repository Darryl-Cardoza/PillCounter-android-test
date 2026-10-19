package com.dispensesure.retail.feature.dispense.data

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.core.faceAuth.data.OperatorName
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottleJson
import com.dispensesure.retail.core.scanning.engine.SavedPhoto
import com.dispensesure.retail.core.utils.common.HelperFunctions
import com.dispensesure.retail.feature.hl7.data.repository.DispenseHl7Repository
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val ORDERED_GTIN = "00300930058123"

@RunWith(RobolectricTestRunner::class)
class DispenseCountRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repository: DispenseCountRepository
    private val operatorNameProvider: OperatorNameProvider = mockk()
    private val dispenseHl7Repository: DispenseHl7Repository = mockk(relaxed = true)

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        coEvery { operatorNameProvider() } returns OperatorName("Jane", "Doe")
        repository = createRepository(db.dispenseTxnDao(), db.dispenseCountDao())
        db.drugMasterDao().insertIgnore(
            DrugMasterEntity(drugId = 1L, drugName = "Aspirin", ndc = "111", gtin = ORDERED_GTIN, drugImagePath = "/images/aspirin.png")
        )
        db.drugMasterDao().insertIgnore(
            DrugMasterEntity(drugId = 2L, drugName = "Generic", ndc = "222", drugImagePath = "/images/generic.png")
        )
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
    }

    private fun createRepository(dispenseTxnDao: DispenseTxnDao, dispenseCountDao: DispenseCountDao) =
        DispenseCountRepository(
            context = ApplicationProvider.getApplicationContext(),
            appDatabase = db,
            dispenseTxnDao = dispenseTxnDao,
            dispenseCountDao = dispenseCountDao,
            drugMasterDao = db.drugMasterDao(),
            operatorNameProvider = operatorNameProvider,
            dispenseHl7Repository = dispenseHl7Repository,
        )

    private suspend fun insertTxn(
        targetCount: Int? = 30,
        bottles: List<DispenseBottle> = emptyList(),
        isComingFromHL7: Boolean? = null,
        isSynced: Boolean? = null,
        isSubstitute: Boolean = false,
        substitutedDrugId: Long? = null,
        rxNo: String? = null,
        refillNo: String? = null,
    ): Long = db.dispenseTxnDao().insertIgnore(
        DispenseTxnEntity(
            status = CountStatus.PARTIAL,
            drugId = 1L,
            targetCount = targetCount,
            bottlesJson = bottles.takeIf { it.isNotEmpty() }?.let { DispenseBottleJson.encode(it) },
            isComingFromHL7 = isComingFromHL7,
            isSynced = isSynced,
            isSubstitute = isSubstitute,
            substitutedDrugId = substitutedDrugId,
            rxNo = rxNo,
            refillNo = refillNo,
        )
    )

    private suspend fun insertCount(
        txnId: Long,
        step: StepState,
        pillCount: Int,
        imagePath: String? = null,
        rawImagePath: String? = null,
        createdAt: Long = System.currentTimeMillis(),
    ): Long = db.dispenseCountDao().insert(
        DispenseCountEntity(
            txnId = txnId, pillCount = pillCount, imagePath = imagePath,
            rawImagePath = rawImagePath, step = step.name, createdAt = createdAt,
        )
    )

    private suspend fun bottlesOf(txnId: Long) =
        DispenseBottleJson.decode(db.dispenseTxnDao().getById(txnId)?.bottlesJson)

    private suspend fun countsOf(txnId: Long, step: StepState) =
        db.dispenseCountDao().observeAllForTxn(txnId, step).first()

    // ───────────────────────── counts ─────────────────────────

    @Test
    fun `addCount saves the count with its step and both photos`() = runTest {
        val txnId = insertTxn()

        val countId = repository.addCount(
            txnId, StepState.TARGET_VERIFICATION, 5, SavedPhoto("/photos/count.jpg", "/photos/count_raw.jpg")
        )

        val savedCount = countsOf(txnId, StepState.TARGET_VERIFICATION).single()
        assertEquals(countId, savedCount.countId)
        assertEquals(5, savedCount.pillCount)
        assertEquals("/photos/count.jpg", savedCount.imagePath)
        assertEquals("/photos/count_raw.jpg", savedCount.rawImagePath)
    }

    @Test
    fun `addCount links the new count to the last scanned bottle`() = runTest {
        val txnId = insertTxn(
            bottles = listOf(DispenseBottle(lotNumber = "OLD", countIds = listOf(1L)), DispenseBottle(lotNumber = "L1"))
        )

        val countId = repository.addCount(txnId, StepState.TARGET_VERIFICATION, 5, SavedPhoto(null, null))

        val bottles = bottlesOf(txnId)
        assertEquals(listOf(1L), bottles[0].countIds)
        assertEquals(listOf(countId), bottles[1].countIds)
    }

    @Test
    fun `addCount with no bottle on file links nothing`() = runTest {
        val txnId = insertTxn()

        repository.addCount(txnId, StepState.TARGET_VERIFICATION, 5, SavedPhoto(null, null))

        assertNull(db.dispenseTxnDao().getById(txnId)?.bottlesJson)
    }

    @Test
    fun `deleteCount removes the photo files, the row and its bottle link`() = runTest {
        val countPhoto = temporaryFolder.newFile("count.jpg")
        val rawCountPhoto = temporaryFolder.newFile("count_raw.jpg")
        val txnId = insertTxn()
        val deletedCountId = insertCount(txnId, StepState.TARGET_VERIFICATION, 5, countPhoto.absolutePath, rawCountPhoto.absolutePath)
        val keptCountId = insertCount(txnId, StepState.TARGET_VERIFICATION, 3)
        db.dispenseTxnDao().updateBottles(
            txnId, DispenseBottleJson.encode(listOf(DispenseBottle(countIds = listOf(deletedCountId, keptCountId))))
        )

        repository.deleteCount(txnId, deletedCountId)

        assertFalse(countPhoto.exists())
        assertFalse(rawCountPhoto.exists())
        assertEquals(listOf(keptCountId), countsOf(txnId, StepState.TARGET_VERIFICATION).map { it.countId })
        assertEquals(listOf(keptCountId), bottlesOf(txnId).single().countIds)
    }

    @Test
    fun `deleteCount leaves bottles alone when the count isn't linked`() = runTest {
        val txnId = insertTxn(bottles = listOf(DispenseBottle(lotNumber = "L1", countIds = listOf(99L))))
        val countId = insertCount(txnId, StepState.TARGET_VERIFICATION, 5)

        repository.deleteCount(txnId, countId)

        assertEquals(listOf(99L), bottlesOf(txnId).single().countIds)
    }

    @Test
    fun `observeCounts gives one step's counts, newest first`() = runTest {
        val txnId = insertTxn()
        insertCount(txnId, StepState.TARGET_VERIFICATION, 5, createdAt = 1L)
        val newestCountId = insertCount(txnId, StepState.TARGET_VERIFICATION, 3, createdAt = 2L)
        insertCount(txnId, StepState.CONTAINER_INITIATE, 40)

        val stepCounts = repository.observeCounts(txnId, StepState.TARGET_VERIFICATION).first()

        assertEquals(listOf(newestCountId), stepCounts.take(1).map { it.txnDetailId })
        assertEquals(listOf(3, 5), stepCounts.map { it.count })
        assertTrue(stepCounts.all { it.type == StepState.TARGET_VERIFICATION })
    }

    @Test
    fun `stepPillTotal sums only that step`() = runTest {
        val txnId = insertTxn()
        insertCount(txnId, StepState.TARGET_VERIFICATION, 5)
        insertCount(txnId, StepState.TARGET_VERIFICATION, 3)
        insertCount(txnId, StepState.CONTAINER_INITIATE, 40)

        assertEquals(8, repository.stepPillTotal(txnId, StepState.TARGET_VERIFICATION))
        assertEquals(40, repository.stepPillTotal(txnId, StepState.CONTAINER_INITIATE))
        assertEquals(48, repository.totalPillCount(txnId))
    }

    // ───────────────────────── photo label ─────────────────────────

    @Test
    fun `the photo label names the ordered drug, the Rx and the active bottle`() = runTest {
        val txnId = insertTxn(
            rxNo = "123",
            refillNo = "2",
            bottles = listOf(
                DispenseBottle(lotNumber = "OLD"),
                DispenseBottle(lotNumber = "L1", serialNumber = "S1", expirationDate = "04-30-2028"),
            ),
        )

        val photoLabel = repository.countPhotoLabel(txnId, StepState.TARGET_VERIFICATION)

        assertEquals("Aspirin", photoLabel.drugName)
        assertEquals("/images/aspirin.png", photoLabel.drugImagePath)
        assertEquals("111", photoLabel.requestedNdc)
        assertEquals("111", photoLabel.dispensedNdc)
        assertEquals("L1", photoLabel.lotNumber)
        assertEquals("S1", photoLabel.serialNumber)
        assertEquals("04-30-2028", photoLabel.expirationDate)
        assertEquals("123-2", photoLabel.rxRefill)
        assertEquals("TARGET_VERIFICATION", photoLabel.stepLabel)
        assertFalse(photoLabel.isStockCount)
    }

    @Test
    fun `the photo label follows the substitute when there is one`() = runTest {
        val txnId = insertTxn(isSubstitute = true, substitutedDrugId = 2L)

        val photoLabel = repository.countPhotoLabel(txnId, StepState.TARGET_VERIFICATION)

        assertEquals("Generic", photoLabel.drugName)
        assertEquals("/images/generic.png", photoLabel.drugImagePath)
        assertEquals("111", photoLabel.requestedNdc)
        assertEquals("222", photoLabel.dispensedNdc)
    }

    @Test
    fun `a substitute id without the substitute flag is ignored`() = runTest {
        val txnId = insertTxn(isSubstitute = false, substitutedDrugId = 2L)

        val photoLabel = repository.countPhotoLabel(txnId, StepState.TARGET_VERIFICATION)

        assertEquals("Aspirin", photoLabel.drugName)
        assertEquals("111", photoLabel.dispensedNdc)
    }

    // ───────────────────────── bottles ─────────────────────────

    @Test
    fun `addBottle appends the new active bottle`() = runTest {
        val txnId = insertTxn(bottles = listOf(DispenseBottle(lotNumber = "L1")))

        repository.addBottle(txnId, DispenseBottle(lotNumber = "L2"))

        assertEquals(listOf("L1", "L2"), bottlesOf(txnId).map { it.lotNumber })
    }

    @Test
    fun `replaceLastBottle swaps the active bottle`() = runTest {
        val txnId = insertTxn(bottles = listOf(DispenseBottle(lotNumber = "L1"), DispenseBottle(lotNumber = "L2")))

        repository.replaceLastBottle(txnId, DispenseBottle(lotNumber = "L3"))

        assertEquals(listOf("L1", "L3"), bottlesOf(txnId).map { it.lotNumber })
    }

    @Test
    fun `replaceLastBottle with no bottle on file adds it`() = runTest {
        val txnId = insertTxn()

        repository.replaceLastBottle(txnId, DispenseBottle(lotNumber = "L1"))

        assertEquals(listOf("L1"), bottlesOf(txnId).map { it.lotNumber })
    }

    /** Runs [writeBottles] on a mocked txn DAO and returns whether its bottle write ran inside a transaction. */
    private suspend fun bottleWriteIsInTransaction(writeBottles: suspend (DispenseCountRepository) -> Unit): Boolean {
        val dispenseTxnDao: DispenseTxnDao = mockk(relaxed = true)
        coEvery { dispenseTxnDao.getById(44L) } returns DispenseTxnEntity(
            txnId = 44L,
            status = CountStatus.PARTIAL,
        )
        var isInTransaction = false
        coEvery { dispenseTxnDao.updateBottles(44L, any(), any()) } answers {
            isInTransaction = db.inTransaction()
        }

        writeBottles(createRepository(dispenseTxnDao, db.dispenseCountDao()))

        return isInTransaction
    }

    @Test
    fun `addBottle rewrites the bottle list inside a transaction`() = runTest {
        assertTrue(
            bottleWriteIsInTransaction { repository ->
                repository.addBottle(
                    44L,
                    DispenseBottle(lotNumber = "L1"),
                )
            },
        )
    }

    @Test
    fun `replaceLastBottle rewrites the bottle list inside a transaction`() = runTest {
        assertTrue(
            bottleWriteIsInTransaction { repository ->
                repository.replaceLastBottle(
                    44L,
                    DispenseBottle(lotNumber = "L1"),
                )
            },
        )
    }

    @Test
    fun `loadBottles is null for a missing txn`() = runTest {
        assertNull(repository.loadBottles(999L))
    }

    @Test
    fun `findLocalDrug matches the GTIN first, then the NDC`() = runTest {
        assertEquals(1L, repository.findLocalDrug(ORDERED_GTIN)?.drugId)
        assertEquals(2L, repository.findLocalDrug("222")?.drugId)
        assertNull(repository.findLocalDrug("999"))
    }

    // ───────────────────────── vial photo ─────────────────────────

    @Test
    fun `saveVialPhoto replaces the earlier vial row`() = runTest {
        mockkObject(HelperFunctions)
        every { HelperFunctions.saveBitmapToFile(any(), any(), any(), any(), any()) } answers {
            "/photos/${thirdArg<String>()}"
        }
        val txnId = insertTxn()
        insertCount(txnId, StepState.VIAL, 0, imagePath = "/photos/old_vial.jpg")
        val vialStill: Bitmap = mockk(relaxed = true)

        repository.saveVialPhoto(txnId, vialStill, grayscale = true)

        val vialRow = countsOf(txnId, StepState.VIAL).single()
        assertEquals(0, vialRow.pillCount)
        assertTrue(vialRow.imagePath!!.startsWith("/photos/txn_detail_"))
        verify { HelperFunctions.saveBitmapToFile(any(), vialStill, any(), "transaction_details", true) }
    }

    @Test
    fun `a recycled vial still is saved as a row without a file`() = runTest {
        mockkObject(HelperFunctions)
        val txnId = insertTxn()
        val vialStill: Bitmap = mockk(relaxed = true)
        every { vialStill.isRecycled } returns true

        repository.saveVialPhoto(txnId, vialStill, grayscale = false)

        assertNull(countsOf(txnId, StepState.VIAL).single().imagePath)
        verify(exactly = 0) { HelperFunctions.saveBitmapToFile(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `loadVialPhoto is null with no vial row`() = runTest {
        val txnId = insertTxn()

        assertNull(repository.loadVialPhoto(txnId))
    }

    @Test
    fun `loadVialPhoto is null when the vial file is gone`() = runTest {
        val txnId = insertTxn()
        insertCount(txnId, StepState.VIAL, 0, imagePath = "${temporaryFolder.root.absolutePath}/missing.jpg")

        assertNull(repository.loadVialPhoto(txnId))
    }

    // ───────────────────────── reset ─────────────────────────

    @Test
    fun `reset deletes every count, its photos, and the txn's count fields`() = runTest {
        val countPhoto = temporaryFolder.newFile("count.jpg")
        val txnId = insertTxn(bottles = listOf(DispenseBottle(lotNumber = "L1")))
        db.dispenseTxnDao().updateWorkflowStep(txnId, StepState.VIAL.name)
        insertCount(txnId, StepState.TARGET_VERIFICATION, 5, imagePath = countPhoto.absolutePath)

        repository.reset(txnId)

        assertFalse(countPhoto.exists())
        assertEquals(0, repository.totalPillCount(txnId))
        val resetTxn = db.dispenseTxnDao().getById(txnId)!!
        assertNull(resetTxn.workflowStep)
        assertNull(resetTxn.bottlesJson)
        // The order itself stays.
        assertEquals(30, resetTxn.targetCount)
    }

    @Test
    fun `reset is open with no txn`() = runTest {
        assertTrue(repository.observeCanReset(0L).first())
    }

    @Test
    fun `reset is open while the txn is unsynced`() = runTest {
        val txnId = insertTxn(isSynced = false)

        assertTrue(repository.observeCanReset(txnId).first())
    }

    @Test
    fun `reset closes once the txn has synced`() = runTest {
        val txnId = insertTxn(isSynced = true)

        assertFalse(repository.observeCanReset(txnId).first())
    }

    // ───────────────────────── complete ─────────────────────────

    @Test
    fun `complete stores COMPLETED with the operator and sends the dispense`() = runTest {
        val txnId = insertTxn(targetCount = 5)
        insertCount(txnId, StepState.TARGET_VERIFICATION, 5)

        val status = repository.complete(txnId)

        assertEquals(CountStatus.COMPLETED, status)
        val completedTxn = db.dispenseTxnDao().getById(txnId)!!
        assertEquals(CountStatus.COMPLETED, completedTxn.status)
        assertEquals("Jane", completedTxn.operatorFirstName)
        assertEquals("Doe", completedTxn.operatorLastName)
        verify { dispenseHl7Repository.sendDispenseNow(txnId) }
    }

    @Test
    fun `a short dispense is stored PARTIAL even when other steps counted more pills`() = runTest {
        val txnId = insertTxn(targetCount = 20)
        insertCount(txnId, StepState.TARGET_VERIFICATION, 15)
        insertCount(txnId, StepState.CONTAINER_INITIATE, 20)

        assertEquals(CountStatus.PARTIAL, repository.complete(txnId))
        assertEquals(CountStatus.PARTIAL, db.dispenseTxnDao().getById(txnId)?.status)
    }

    @Test
    fun `a dispense with no target is COMPLETED`() = runTest {
        val txnId = insertTxn(targetCount = null)
        insertCount(txnId, StepState.TARGET_VERIFICATION, 3)

        assertEquals(CountStatus.COMPLETED, repository.complete(txnId))
    }

    @Test
    fun `completing an HL7 dispense marks it unsynced`() = runTest {
        val txnId = insertTxn(targetCount = 5, isComingFromHL7 = true, isSynced = true)
        insertCount(txnId, StepState.TARGET_VERIFICATION, 5)

        repository.complete(txnId)

        val completedTxn = db.dispenseTxnDao().getById(txnId)!!
        assertEquals(CountStatus.COMPLETED, completedTxn.status)
        assertEquals(false, completedTxn.isSynced)
    }

    @Test
    fun `complete with nothing counted changes nothing and sends nothing`() = runTest {
        val txnId = insertTxn()

        assertNull(repository.complete(txnId))
        assertEquals(CountStatus.PARTIAL, db.dispenseTxnDao().getById(txnId)?.status)
        verify(exactly = 0) { dispenseHl7Repository.sendDispenseNow(any()) }
    }

    @Test
    fun `complete for a missing txn returns null`() = runTest {
        assertNull(repository.complete(999L))
    }

    @Test
    fun `operator is stamped before the status write that wakes the resend sweep`() = runTest {
        // observePendingHl7Txn fires on status = COMPLETED. If the operator lands after
        // that write, the resend sweep can read the row first and send a blank operator.
        val dispenseTxnDao: DispenseTxnDao = mockk(relaxed = true)
        val dispenseCountDao: DispenseCountDao = mockk(relaxed = true)
        coEvery { dispenseCountDao.getTotalPillCountForTxn(44L) } returns 5
        coEvery { dispenseCountDao.getPillCountForStep(44L, StepState.TARGET_VERIFICATION.name) } returns 5
        coEvery { dispenseTxnDao.getById(44L) } returns DispenseTxnEntity(
            txnId = 44L, status = CountStatus.PARTIAL, targetCount = 5,
        )

        createRepository(dispenseTxnDao, dispenseCountDao).complete(44L)

        coVerifyOrder {
            dispenseTxnDao.updateOperatorName(44L, "Jane", "Doe", any())
            dispenseTxnDao.updateTxnStatus(44L, CountStatus.COMPLETED, any())
            dispenseHl7Repository.sendDispenseNow(44L)
        }
    }
}
