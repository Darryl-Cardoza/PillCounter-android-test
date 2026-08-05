package com.rite.pillcounting.core.faceAuth.data

import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.room.dao.FaceEmbeddingDao
import com.rite.pillcounting.core.room.dao.FaceProfileDao
import com.rite.pillcounting.core.room.models.FaceEmbeddingEntity
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProfileDao : FaceProfileDao {
    val saved = mutableListOf<FaceProfileEntity>()
    private var nextId = 1L
    override suspend fun insert(profile: FaceProfileEntity): Long {
        val id = nextId++
        saved.add(profile.copy(id = id))
        return id
    }
    override suspend fun update(profile: FaceProfileEntity) {
        val i = saved.indexOfFirst { it.id == profile.id }
        if (i >= 0) saved[i] = profile
    }
    override suspend fun delete(profile: FaceProfileEntity) { saved.removeAll { it.id == profile.id } }
    override fun observeAll(): Flow<List<FaceProfileEntity>> = flowOf(saved)
    override suspend fun getEnabled(): List<FaceProfileEntity> = saved.filter { it.isEnabled }
    override suspend fun updateLastUsed(id: Long, timestamp: Long) {
        val i = saved.indexOfFirst { it.id == id }
        if (i >= 0) saved[i] = saved[i].copy(lastUsedAt = timestamp)
    }
}

private class FakeEmbeddingDao : FaceEmbeddingDao {
    val saved = mutableListOf<FaceEmbeddingEntity>()
    override suspend fun insertAll(embeddings: List<FaceEmbeddingEntity>) { saved.addAll(embeddings) }
    override suspend fun getForEnabledProfiles(): List<FaceEmbeddingEntity> = saved
}

class FaceProfileRepositoryTest {

    @Test
    fun `registerProfile stores the profile and one embedding per angle`() = runTest {
        val profileDao = FakeProfileDao()
        val embeddingDao = FakeEmbeddingDao()
        val repo = FaceProfileRepository(profileDao, embeddingDao)

        val id = repo.registerProfile(
            firstName = "Bruce", lastName = "Wayne", email = "bruce@rite.com",
            embeddingsByAngle = mapOf(
                FaceCaptureAngle.FRONT to FloatArray(128) { 1f },
                FaceCaptureAngle.TILT_LEFT to FloatArray(128) { 2f },
                FaceCaptureAngle.TILT_RIGHT to FloatArray(128) { 3f },
            ),
            now = 1000L
        )

        assertEquals(1, profileDao.saved.size)
        assertEquals(id, profileDao.saved[0].id)
        assertEquals(3, embeddingDao.saved.size)
        assertTrue(embeddingDao.saved.all { it.faceProfileId == id })
    }

    @Test
    fun `loadGallery returns every embedding as a float array of length 128`() = runTest {
        val profileDao = FakeProfileDao()
        val embeddingDao = FakeEmbeddingDao()
        val repo = FaceProfileRepository(profileDao, embeddingDao)

        repo.registerProfile(
            "Bruce", "Wayne", null,
            mapOf(FaceCaptureAngle.FRONT to FloatArray(128) { it.toFloat() }),
            now = 0L
        )

        val gallery = repo.loadGallery()
        assertEquals(1, gallery.size)
        assertEquals(128, gallery[0].vec.size)
        assertEquals(5f, gallery[0].vec[5], 1e-4f)
    }
}
