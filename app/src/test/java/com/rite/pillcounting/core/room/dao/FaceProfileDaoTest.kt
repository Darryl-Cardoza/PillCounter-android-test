package com.rite.pillcounting.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FaceProfileDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FaceProfileDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.faceProfileDao()
    }

    @After
    fun tearDown() { db.close() }

    private fun profile(name: String, enabled: Boolean = true) = FaceProfileEntity(
        firstName = name, lastName = "Test", email = "$name@rite.com",
        isEnabled = enabled, createdAt = 1000L
    )

    @Test
    fun `insert returns generated id and observeAll emits it`() = runTest {
        val id = dao.insert(profile("Bruce"))
        assertTrue(id > 0L)
        assertEquals(1, dao.getEnabled().size)
    }

    @Test
    fun `getEnabled excludes disabled profiles`() = runTest {
        dao.insert(profile("Bruce", enabled = true))
        dao.insert(profile("Clark", enabled = false))
        val enabled = dao.getEnabled()
        assertEquals(1, enabled.size)
        assertEquals("Bruce", enabled[0].firstName)
    }

    @Test
    fun `updateLastUsed stamps the timestamp`() = runTest {
        val id = dao.insert(profile("Bruce"))
        dao.updateLastUsed(id, 5000L)
        assertEquals(5000L, dao.getEnabled().first().lastUsedAt)
    }

    @Test
    fun `delete removes the profile`() = runTest {
        val saved = profile("Bruce").copy(id = dao.insert(profile("Bruce")))
        dao.delete(saved)
        assertEquals(0, dao.getEnabled().size)
    }
}
