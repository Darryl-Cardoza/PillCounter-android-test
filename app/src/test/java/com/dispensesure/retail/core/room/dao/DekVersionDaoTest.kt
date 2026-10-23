package com.dispensesure.retail.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.models.DekVersionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DekVersionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DekVersionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dekVersionDao()
    }

    @After
    fun tearDown() { db.close() }

    private fun row(version: Int = 1, kekKeyId: String = "kek-1", isRegistered: Boolean = false) = DekVersionEntity(
        dekKeyId = "dek-dev-1", version = version, wrappedDek = byteArrayOf(version.toByte()),
        kekKeyId = kekKeyId, kekVersion = 1, kekAlgorithm = "AES-256-GCM",
        isActive = true, isRegistered = isRegistered, createdAt = 1000L
    )

    @Test
    fun `insertAsActive keeps the old row but leaves one active`() = runTest {
        dao.insertAsActive(row(kekKeyId = "local-bootstrap"))
        dao.insertAsActive(row(kekKeyId = "kek-1"))

        val rows = dao.getAll()
        assertEquals(listOf(false, true), rows.map { it.isActive })
        assertEquals("kek-1", dao.getActive()!!.kekKeyId)
    }

    @Test
    fun `the oldest server-wrapped row skips the bootstrap row`() = runTest {
        dao.insertAsActive(row(kekKeyId = "local-bootstrap"))
        dao.insertAsActive(row(kekKeyId = "kek-1"))
        dao.insertAsActive(row(kekKeyId = "kek-2"))

        assertEquals("kek-1", dao.getOldestServerWrapped(1, "local-bootstrap")!!.kekKeyId)
        assertNull(dao.getOldestServerWrapped(2, "local-bootstrap"))
    }

    @Test
    fun `marking a version registered skips bootstrap rows and other versions`() = runTest {
        dao.insertAsActive(row(version = 1, kekKeyId = "local-bootstrap"))
        dao.insertAsActive(row(version = 1, kekKeyId = "kek-1"))
        dao.insertAsActive(row(version = 2, kekKeyId = "kek-1"))

        dao.markVersionRegistered(1, "local-bootstrap")

        assertEquals(listOf(false, true, false), dao.getAll().map { it.isRegistered })
    }

    @Test
    fun `renumbering moves only the unregistered rows of that version`() = runTest {
        dao.insertAsActive(row(version = 1, kekKeyId = "kek-1", isRegistered = true))
        dao.insertAsActive(row(version = 1, kekKeyId = "local-bootstrap"))
        dao.insertAsActive(row(version = 1, kekKeyId = "kek-2"))

        dao.renumberUnregistered(oldVersion = 1, newVersion = 4)

        assertEquals(listOf(1, 4, 4), dao.getAll().map { it.version })
    }
}
