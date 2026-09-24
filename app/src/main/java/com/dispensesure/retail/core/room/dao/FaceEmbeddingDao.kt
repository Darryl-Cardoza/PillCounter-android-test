package com.dispensesure.retail.core.room.dao

import androidx.room.Dao
import androidx.room.Query
import com.dispensesure.retail.core.room.models.FaceEmbeddingEntity

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
 * - [getAll] is the read the duplicate-enrollment check uses.
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

    /**
     * Reads every stored embedding, enabled or not.
     *
     * A disabled profile is still on file, so the duplicate-enrollment check has to
     * see it — otherwise the same face is silently enrolled a second time.
     *
     * @return Every embedding row.
     */
    @Query("SELECT * FROM face_embeddings")
    suspend fun getAll(): List<FaceEmbeddingEntity>
}
