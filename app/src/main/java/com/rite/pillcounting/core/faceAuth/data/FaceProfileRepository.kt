package com.rite.pillcounting.core.faceAuth.data

import com.rite.pillcounting.core.faceAuth.logic.FaceMatcher
import com.rite.pillcounting.core.faceAuth.logic.GalleryEntry
import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.room.dao.FaceEmbeddingDao
import com.rite.pillcounting.core.room.dao.FaceProfileDao
import com.rite.pillcounting.core.room.models.FaceEmbeddingEntity
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/**
 * Repository for enrolled face profiles and their embeddings.
 *
 * Description:
 * Direct Kotlin equivalent of `standalone_face_tf.py`'s `Store` class, backed by
 * Room instead of raw sqlite3. Owns the [FloatArray]↔[ByteArray] conversion for
 * the embedding blob (128 float32s, little-endian).
 *
 * What it does:
 * - [registerProfile] saves a new profile plus its 3 angle-tagged embeddings.
 * - [loadGallery] flattens every enabled profile's embeddings into [GalleryEntry]
 *   rows ready for [com.rite.pillcounting.core.faceAuth.logic.FaceMatcher.identify].
 */
class FaceProfileRepository @Inject constructor(
    private val profileDao: FaceProfileDao,
    private val embeddingDao: FaceEmbeddingDao
) {
    /**
     * Observes every enrolled profile for the Quick Access Users list.
     *
     * @return A [Flow] emitting the full profile list on every change.
     */
    fun observeProfiles(): Flow<List<FaceProfileEntity>> = profileDao.observeAll()

    /**
     * Registers a new face profile with one embedding per capture angle.
     *
     * @param firstName Typed at registration.
     * @param lastName Typed at registration.
     * @param email Snapshot of the logged-in session's account email, or null if unavailable.
     * @param embeddingsByAngle One 128-float embedding per [FaceCaptureAngle] captured.
     * @param now Epoch millis to stamp as `createdAt`.
     * @param faceImagePath Absolute path to the FRONT-angle face JPEG in internal storage, or null if not available.
     * @return The new profile's Room id.
     *
     * Example Usage:
     * val id = repository.registerProfile("Bruce", "Wayne", email, embeddingsByAngle, now)
     */
    suspend fun registerProfile(
        firstName: String,
        lastName: String,
        email: String?,
        embeddingsByAngle: Map<FaceCaptureAngle, FloatArray>,
        now: Long,
        faceImagePath: String? = null
    ): Long = profileDao.insertWithEmbeddings(
        FaceProfileEntity(
            firstName = firstName,
            lastName = lastName,
            email = email,
            createdAt = now,
            faceImagePath = faceImagePath
        )
    ) { id ->
        embeddingsByAngle.map { (angle, vec) ->
            FaceEmbeddingEntity(faceProfileId = id, angle = angle.name, vec = floatArrayToBytes(vec))
        }
    }

    /**
     * Enables or disables a profile's participation in verify matching.
     *
     * @param profile The profile to update.
     * @param enabled New enabled state.
     */
    suspend fun setEnabled(profile: FaceProfileEntity, enabled: Boolean) {
        profileDao.update(profile.copy(isEnabled = enabled))
    }

    /**
     * Deletes a profile; its embeddings cascade-delete with it.
     *
     * @param profile The profile to delete.
     */
    suspend fun deleteProfile(profile: FaceProfileEntity) = profileDao.delete(profile)

    /**
     * Stamps a profile's `lastUsedAt` after a successful verify match.
     *
     * @param faceProfileId The matched profile's Room id.
     * @param now Epoch millis of the match.
     */
    suspend fun markUsed(faceProfileId: Long, now: Long) = profileDao.updateLastUsed(faceProfileId, now)

    /**
     * Reads a single profile by id, straight from the database.
     *
     * Description:
     * Unlike [observeProfiles], this is a one-shot read — it can't be stale from a
     * not-yet-collected [Flow], so it's the right lookup right after [loadGallery]
     * returns a matched id.
     *
     * @param faceProfileId The matched profile's Room id.
     * @return The matching profile, or null if it no longer exists.
     *
     * Example Usage:
     * val profile = repository.getProfile(matchedId)
     */
    suspend fun getProfile(faceProfileId: Long): FaceProfileEntity? = profileDao.getById(faceProfileId)

    /**
     * Loads every embedding of every enabled profile as the verify-time match gallery.
     *
     * @return One [GalleryEntry] per stored embedding (3 per enrolled, enabled profile).
     */
    suspend fun loadGallery(): List<GalleryEntry> =
        embeddingDao.getForEnabledProfiles().map {
            GalleryEntry(it.faceProfileId, bytesToFloatArray(it.vec))
        }

    /**
     * Finds an already-enrolled profile whose face matches [embedding].
     *
     * Description:
     * Used at the FRONT step of registration, to warn when someone is enrolling
     * a face that is already on file. The probe is frontal but the gallery holds
     * every angle, so this is the same comparison verify makes — hence the same
     * [FaceMatcher.MATCH_THRESHOLD].
     *
     * Reads disabled profiles too, unlike [loadGallery]: disabled means "cannot
     * unlock", not "not on file", and enrolling a second profile for the same face
     * leaves [FaceMatcher.identify] picking between them arbitrarily once it is
     * re-enabled.
     *
     * @param embedding The FRONT embedding just captured.
     * @return The matching profile, or null if nothing cleared the threshold.
     *
     * Example Usage:
     * val existing = repository.findExistingMatch(frontEmbedding)
     */
    suspend fun findExistingMatch(embedding: FloatArray): FaceProfileEntity? {
        val gallery = embeddingDao.getAll().map {
            GalleryEntry(it.faceProfileId, bytesToFloatArray(it.vec))
        }
        val matchedId = FaceMatcher.identify(embedding, gallery).faceProfileId ?: return null
        return profileDao.getById(matchedId)
    }

    private fun floatArrayToBytes(vec: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vec) buffer.putFloat(v)
        return buffer.array()
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}
