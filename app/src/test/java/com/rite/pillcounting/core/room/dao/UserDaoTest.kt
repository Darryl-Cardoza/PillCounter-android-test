package com.rite.pillcounting.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.models.UserEntity
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
class UserDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: UserDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.userDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ───────────────────────── insertIgnore ─────────────────────────

    @Test
    fun `insertIgnore returns generated localId for new user`() = runTest {
        val id = dao.insertIgnore(UserEntity(userId = "u1"))
        assertTrue(id > 0L)
    }

    @Test
    fun `insertIgnore ignores conflicting localId and returns minus one`() = runTest {
        dao.insertIgnore(UserEntity(localId = 5L, userId = "first"))
        val second = dao.insertIgnore(UserEntity(localId = 5L, userId = "second"))

        assertEquals(-1L, second)
        assertEquals("first", dao.getByUserId("first")?.userId)
        assertNull(dao.getByUserId("second"))
    }

    // ───────────────────────── update ─────────────────────────

    @Test
    fun `update persists changed fields for existing localId`() = runTest {
        val id = dao.insertIgnore(UserEntity(userId = "u1", fName = "Old"))
        dao.update(UserEntity(localId = id, userId = "u1", fName = "New"))

        val updated = dao.getByUserId("u1")!!
        assertEquals("New", updated.fName)
    }

    @Test
    fun `update on nonexistent localId affects no rows`() = runTest {
        dao.insertIgnore(UserEntity(userId = "u1", fName = "Kept"))
        dao.update(UserEntity(localId = 999L, userId = "u1", fName = "Changed"))

        assertEquals("Kept", dao.getByUserId("u1")?.fName)
    }

    // ───────────────────────── getByUserId ─────────────────────────

    @Test
    fun `getByUserId returns null when no user exists`() = runTest {
        assertNull(dao.getByUserId("missing"))
    }

    @Test
    fun `getByUserId returns matching user`() = runTest {
        dao.insertIgnore(UserEntity(userId = "u1", fName = "Alice"))
        val result = dao.getByUserId("u1")
        assertEquals("u1", result?.userId)
        assertEquals("Alice", result?.fName)
    }

    // ───────────────────────── observeByLocalId ─────────────────────────

    @Test
    fun `observeByLocalId emits null when localId does not exist`() = runTest {
        dao.observeByLocalId(123L).test {
            assertNull(awaitItem())
        }
    }

    @Test
    fun `observeByLocalId emits current user and updates on change`() = runTest {
        val id = dao.insertIgnore(UserEntity(userId = "u1", fName = "Initial"))

        dao.observeByLocalId(id).test {
            assertEquals("Initial", awaitItem()?.fName)

            dao.update(UserEntity(localId = id, userId = "u1", fName = "Updated"))
            assertEquals("Updated", awaitItem()?.fName)
        }
    }

    // ───────────────────────── upsertPreservingLocalId ─────────────────────────

    @Test
    fun `upsertPreservingLocalId inserts new user when none exists`() = runTest {
        val id = dao.upsertPreservingLocalId(UserEntity(userId = "new-user", fName = "Fresh"))

        assertTrue(id > 0L)
        val stored = dao.getByUserId("new-user")
        assertEquals(id, stored?.localId)
        assertEquals("Fresh", stored?.fName)
    }

    @Test
    fun `upsertPreservingLocalId updates existing user while preserving localId`() = runTest {
        val originalId = dao.insertIgnore(UserEntity(userId = "existing", fName = "Old"))

        val returnedId = dao.upsertPreservingLocalId(
            UserEntity(localId = 0L, userId = "existing", fName = "New")
        )

        assertEquals(originalId, returnedId)
        val stored = dao.getByUserId("existing")
        assertEquals(originalId, stored?.localId)
        assertEquals("New", stored?.fName)
    }

    @Test
    fun `upsertPreservingLocalId does not change localId across repeated upserts`() = runTest {
        val firstId = dao.upsertPreservingLocalId(UserEntity(userId = "stable", fName = "V1"))
        val secondId = dao.upsertPreservingLocalId(UserEntity(userId = "stable", fName = "V2"))
        val thirdId = dao.upsertPreservingLocalId(UserEntity(userId = "stable", fName = "V3"))

        assertEquals(firstId, secondId)
        assertEquals(secondId, thirdId)
        assertEquals("V3", dao.getByUserId("stable")?.fName)
    }

    @Test
    fun `upsertPreservingLocalId keeps distinct users with distinct localIds`() = runTest {
        val idA = dao.upsertPreservingLocalId(UserEntity(userId = "userA"))
        val idB = dao.upsertPreservingLocalId(UserEntity(userId = "userB"))

        assertNotEquals(idA, idB)
        assertEquals("userA", dao.getByUserId("userA")?.userId)
        assertEquals("userB", dao.getByUserId("userB")?.userId)
    }
}
