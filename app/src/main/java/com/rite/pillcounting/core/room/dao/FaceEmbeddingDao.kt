package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.rite.pillcounting.core.room.models.FaceEmbeddingEntity

/**
 * Data Access Object for [FaceEmbeddingEntity] rows.
 *
 * Description:
 * Stores and retrieves the 3 per-angle embeddings belonging to each face profile.
 *
 * What it does:
 * - [insertAll] persists the 3 embeddings captured at registration in one call.
 * - [getForEnabledProfiles] is the read `FaceMatcher` uses to build its match gallery.
 *
 * Example Usage:
 * faceEmbeddingDao.insertAll(listOf(frontEmbedding, leftEmbedding, rightEmbedding))
 */
@Dao
interface FaceEmbeddingDao {

    /**
     * Inserts multiple embeddings in one transaction-backed call.
     *
     * @param embeddings The embeddings to insert.
     */
    @Insert
    suspend fun insertAll(embeddings: List<FaceEmbeddingEntity>)

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
