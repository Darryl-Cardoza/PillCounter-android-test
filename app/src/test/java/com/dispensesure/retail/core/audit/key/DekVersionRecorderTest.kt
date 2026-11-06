package com.dispensesure.retail.core.audit.key

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.DekVersionDao
import com.dispensesure.retail.core.security.DatabaseKeyProvider
import com.dispensesure.retail.core.security.DekWrap
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DekVersionRecorderTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DekVersionDao
    private lateinit var recorder: DekVersionRecorder

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dekVersionDao()
        recorder = DekVersionRecorder(mockk(relaxed = true), dao)
        mockkObject(DatabaseKeyProvider)
        every { DatabaseKeyProvider.createDekIfMissing(any()) } returns Unit
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
    }

    private fun storedWrap(wrappedDek: ByteArray, kekKeyId: String = "kek-1", kekVersion: Int = 1) {
        every { DatabaseKeyProvider.currentWrap(any()) } returns DekWrap(wrappedDek, kekKeyId, kekVersion, "AES-256-GCM")
    }

    @Test
    fun `the dek is created before its wrap is read`() = runTest {
        storedWrap(byteArrayOf(1))
        recorder.recordCurrentWrap("dev-1")
        verifyOrder {
            DatabaseKeyProvider.createDekIfMissing(any())
            DatabaseKeyProvider.currentWrap(any())
        }
    }

    @Test
    fun `recording never unwraps or re-keys the dek`() = runTest {
        storedWrap(byteArrayOf(1))
        recorder.recordCurrentWrap("dev-1")
        verify(exactly = 0) { DatabaseKeyProvider.getOrCreateDatabasePassphrase(any()) }
        verify(exactly = 0) { DatabaseKeyProvider.unwrapCurrentDek(any()) }
    }

    @Test
    fun `nothing stored yet records nothing`() = runTest {
        every { DatabaseKeyProvider.currentWrap(any()) } returns null
        recorder.recordCurrentWrap("dev-1")
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun `an empty table starts the dek at version 1, unregistered`() = runTest {
        storedWrap(byteArrayOf(1), kekKeyId = "local-bootstrap", kekVersion = 0)

        recorder.recordCurrentWrap("dev-1")

        val row = dao.getActive()!!
        assertEquals("dek-dev-1", row.dekKeyId)
        assertEquals(1, row.version)
        assertArrayEquals(byteArrayOf(1), row.wrappedDek)
        assertEquals("local-bootstrap", row.kekKeyId)
        assertEquals(0, row.kekVersion)
        assertEquals("AES-256-GCM", row.kekAlgorithm)
        assertEquals(false, row.isRegistered)
    }

    @Test
    fun `the same wrap again adds no row`() = runTest {
        storedWrap(byteArrayOf(1))
        recorder.recordCurrentWrap("dev-1")
        recorder.recordCurrentWrap("dev-1")
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `a kek rotation keeps the version and the registration`() = runTest {
        storedWrap(byteArrayOf(1), kekKeyId = "kek-1", kekVersion = 1)
        recorder.recordCurrentWrap("dev-1")
        dao.markVersionRegistered(1, DatabaseKeyProvider.BOOTSTRAP_KEK_ID)

        storedWrap(byteArrayOf(2), kekKeyId = "kek-2", kekVersion = 2)
        recorder.recordCurrentWrap("dev-1")

        val rows = dao.getAll()
        assertEquals(listOf(1, 1), rows.map { it.version })
        assertEquals(listOf(false, true), rows.map { it.isActive })
        assertEquals(listOf(true, true), rows.map { it.isRegistered })
        assertEquals("kek-2", rows[1].kekKeyId)
    }

    @Test
    fun `a new wrap of an unregistered version stays unregistered`() = runTest {
        storedWrap(byteArrayOf(1), kekKeyId = "local-bootstrap", kekVersion = 0)
        recorder.recordCurrentWrap("dev-1")
        storedWrap(byteArrayOf(2), kekKeyId = "kek-1", kekVersion = 1)
        recorder.recordCurrentWrap("dev-1")

        assertEquals(false, dao.getActive()!!.isRegistered)
    }
}
