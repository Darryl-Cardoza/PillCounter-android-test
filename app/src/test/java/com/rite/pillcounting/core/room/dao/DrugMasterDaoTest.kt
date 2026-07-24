package com.rite.pillcounting.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DrugMasterDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DrugMasterDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.drugMasterDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ───────────────────────── insertIgnore ─────────────────────────

    @Test
    fun `insertIgnore returns generated row id for new drug`() = runTest {
        val id = dao.insertIgnore(DrugMasterEntity(ndc = "111"))
        assertTrue(id > 0)
    }

    @Test
    fun `insertIgnore returns minus one on ndc conflict`() = runTest {
        dao.insertIgnore(DrugMasterEntity(ndc = "111", drugName = "First"))
        val secondResult = dao.insertIgnore(DrugMasterEntity(ndc = "111", drugName = "Second"))

        assertEquals(-1L, secondResult)
        val stored = dao.getDrugByNdc("111")
        assertEquals("First", stored?.drugName)
    }

    // ───────────────────────── insertAll ─────────────────────────

    @Test
    fun `insertAll inserts multiple drugs and returns their ids`() = runTest {
        val ids = dao.insertAll(
            listOf(
                DrugMasterEntity(ndc = "1"),
                DrugMasterEntity(ndc = "2"),
                DrugMasterEntity(ndc = "3")
            )
        )
        assertEquals(3, ids.size)
        assertTrue(ids.all { it > 0 })
    }

    @Test
    fun `insertAll with empty list inserts nothing`() = runTest {
        val ids = dao.insertAll(emptyList())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `insertAll ignores conflicting entries and returns minus one for them`() = runTest {
        dao.insertIgnore(DrugMasterEntity(ndc = "1"))
        val ids = dao.insertAll(
            listOf(
                DrugMasterEntity(ndc = "1"),
                DrugMasterEntity(ndc = "2")
            )
        )
        assertEquals(-1L, ids[0])
        assertTrue(ids[1] > 0)
    }

    // ───────────────────────── update ─────────────────────────

    @Test
    fun `update modifies existing row matched by drugId`() = runTest {
        val id = dao.insertIgnore(DrugMasterEntity(ndc = "1", drugName = "Old"))
        val existing = dao.getDrugById(id)!!
        dao.update(existing.copy(drugName = "New"))

        assertEquals("New", dao.getDrugById(id)?.drugName)
    }

    // ───────────────────────── queries ─────────────────────────

    @Test
    fun `getDrugByNdc returns null when not found`() = runTest {
        assertNull(dao.getDrugByNdc("missing"))
    }

    @Test
    fun `getDrugByNdc returns matching entity`() = runTest {
        dao.insertIgnore(DrugMasterEntity(ndc = "555", drugName = "Aspirin"))
        val result = dao.getDrugByNdc("555")
        assertEquals("Aspirin", result?.drugName)
    }

    @Test
    fun `getDrugByGtin returns null when not found`() = runTest {
        assertNull(dao.getDrugByGtin("missing-gtin"))
    }

    @Test
    fun `getDrugByGtin returns matching entity`() = runTest {
        dao.insertIgnore(DrugMasterEntity(ndc = "1", gtin = "GTIN1"))
        val result = dao.getDrugByGtin("GTIN1")
        assertEquals("GTIN1", result?.gtin)
    }

    @Test
    fun `getDrugIdByNdc returns null when not found`() = runTest {
        assertNull(dao.getDrugIdByNdc("missing"))
    }

    @Test
    fun `getDrugIdByNdc returns id when found`() = runTest {
        val id = dao.insertIgnore(DrugMasterEntity(ndc = "777"))
        assertEquals(id, dao.getDrugIdByNdc("777"))
    }

    @Test
    fun `getDrugById returns null for null id`() = runTest {
        assertNull(dao.getDrugById(null))
    }

    @Test
    fun `getDrugById returns null for nonexistent id`() = runTest {
        assertNull(dao.getDrugById(999L))
    }

    @Test
    fun `getDrugById returns matching entity`() = runTest {
        val id = dao.insertIgnore(DrugMasterEntity(ndc = "1"))
        assertEquals(id, dao.getDrugById(id)?.drugId)
    }

    @Test
    fun `getAllDrugIds respects limit`() = runTest {
        dao.insertIgnore(DrugMasterEntity(ndc = "1"))
        dao.insertIgnore(DrugMasterEntity(ndc = "2"))
        dao.insertIgnore(DrugMasterEntity(ndc = "3"))

        val ids = dao.getAllDrugIds(2)
        assertEquals(2, ids.size)
    }

    @Test
    fun `getAllDrugIds returns empty list when table is empty`() = runTest {
        assertTrue(dao.getAllDrugIds(10).isEmpty())
    }

    // ───────────────────────── upsertPreservingId ─────────────────────────

    @Test
    fun `upsertPreservingId inserts new drug when ndc not present`() = runTest {
        val id = dao.upsertPreservingId(DrugMasterEntity(ndc = "new-ndc", drugName = "Drug A"))

        assertTrue(id > 0)
        val stored = dao.getDrugById(id)!!
        assertEquals("Drug A", stored.drugName)
    }

    @Test
    fun `upsertPreservingId reuses existing drugId and updates fields on matching ndc`() = runTest {
        val firstId = dao.upsertPreservingId(DrugMasterEntity(ndc = "dup", drugName = "Old Name"))
        val secondId = dao.upsertPreservingId(DrugMasterEntity(ndc = "dup", drugName = "New Name"))

        assertEquals(firstId, secondId)
        val stored = dao.getDrugById(firstId)!!
        assertEquals("New Name", stored.drugName)
    }

    @Test
    fun `upsertPreservingId normalizes empty drugType to null`() = runTest {
        val id = dao.upsertPreservingId(DrugMasterEntity(ndc = "empty-type", drugType = ""))

        val stored = dao.getDrugById(id)!!
        assertNull(stored.drugType)
    }

    @Test
    fun `upsertPreservingId preserves non-empty drugType`() = runTest {
        val id = dao.upsertPreservingId(DrugMasterEntity(ndc = "typed", drugType = "OTC"))

        val stored = dao.getDrugById(id)!!
        assertEquals("OTC", stored.drugType)
    }

    @Test
    fun `upsertPreservingId keeps existing image path when new drug has null image path`() = runTest {
        val firstId = dao.upsertPreservingId(
            DrugMasterEntity(ndc = "img", drugImagePath = "/path/existing.webp")
        )
        val secondId = dao.upsertPreservingId(
            DrugMasterEntity(ndc = "img", drugName = "Updated", drugImagePath = null)
        )

        assertEquals(firstId, secondId)
        val stored = dao.getDrugById(firstId)!!
        assertEquals("/path/existing.webp", stored.drugImagePath)
        assertEquals("Updated", stored.drugName)
    }

    @Test
    fun `upsertPreservingId overwrites image path when new drug provides one`() = runTest {
        val firstId = dao.upsertPreservingId(
            DrugMasterEntity(ndc = "img2", drugImagePath = "/path/old.webp")
        )
        val secondId = dao.upsertPreservingId(
            DrugMasterEntity(ndc = "img2", drugImagePath = "/path/new.webp")
        )

        assertEquals(firstId, secondId)
        assertEquals("/path/new.webp", dao.getDrugById(firstId)?.drugImagePath)
    }

    @Test
    fun `upsertPreservingId called for different ndcs produces distinct drugIds`() = runTest {
        val firstId = dao.upsertPreservingId(DrugMasterEntity(ndc = "ndc-a"))
        val secondId = dao.upsertPreservingId(DrugMasterEntity(ndc = "ndc-b"))

        assertNotEquals(firstId, secondId)
    }
}
