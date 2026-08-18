package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Query
import com.rite.pillcounting.core.room.models.FaceEmbeddingEntity

/**
 * Data Access Object for [FaceEmbeddingEntity] reads.
 *
 * Description:
 * Retrieves the per-angle embeddings belonging to each face profile. Writes go
 * through [FaceProfileDao.insertWithEmbeddings] so profile + embeddings land in
 * one transaction.
 *
 * What it does:
 * - [getForEnabledProfiles] is the read `FaceMatcher` uses to build its match gallery.
 */
@Dao
interface FaceEmbeddingDao {

    /**
     * Reads every embedding belonging to profiles that are currently enabled.
     *
     * @return Embedding rows joined against enabled profiles only.
     */
    @Query(
        """SELECT face_embeddings.* FROM face_embeddings
           INNER JOIN face_profiles ON face_profiles.id = face_embeddings.faceProfileId
           WHERE face_profiles.isEnabled = 1"""
    )
    suspend fun getForEnabledProfiles(): List<FaceEmbeddingEntity>
}
