package com.rite.pillcounting.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.models.FaceEmbeddingEntity
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FaceEmbeddingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var profileDao: FaceProfileDao
    private lateinit var embeddingDao: FaceEmbeddingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        profileDao = db.faceProfileDao()
        embeddingDao = db.faceEmbeddingDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `getForEnabledProfiles returns only embeddings of enabled profiles`() = runTest {
        val enabledId = profileDao.insert(
            FaceProfileEntity(firstName = "Bruce", lastName = "Wayne", email = null, createdAt = 0L)
        )
        val disabledId = profileDao.insert(
            FaceProfileEntity(firstName = "Clark", lastName = "Kent", email = null, isEnabled = false, createdAt = 0L)
        )
        embeddingDao.insertAll(listOf(
            FaceEmbeddingEntity(faceProfileId = enabledId, angle = "FRONT", vec = ByteArray(4)),
            FaceEmbeddingEntity(faceProfileId = disabledId, angle = "FRONT", vec = ByteArray(4)),
        ))

        val result = embeddingDao.getForEnabledProfiles()
        assertEquals(1, result.size)
        assertEquals(enabledId, result[0].faceProfileId)
    }
}
