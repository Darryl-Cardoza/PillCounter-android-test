package com.rite.pillcounting.core.faceAuth.logic

import kotlin.math.sqrt

/** One stored embedding in the match gallery, tagged with which profile it belongs to. */
data class GalleryEntry(val faceProfileId: Long, val vec: FloatArray)

/** Outcome of a 1:N identify() call. [faceProfileId] is null when [bestScore] didn't clear [FaceMatcher.MATCH_THRESHOLD]. */
data class MatchResult(val faceProfileId: Long?, val bestScore: Float)

/**
 * 1:N cosine-similarity face matching against a gallery of enrolled embeddings.
 *
 * Description:
 * Direct Kotlin port of `standalone_face_tf.py`'s `normalize()`, `cosine()`, and
 * `identify()`. A probe embedding is compared against every gallery entry (which,
 * for this feature, is every embedding of every enabled face profile — 3 rows per
 * profile, one per capture angle); the single best-scoring entry wins.
 *
 * What it does:
 * - [normalize] L2-normalizes a vector.
 * - [cosine] computes cosine similarity between two normalized vectors.
 * - [identify] finds the best-scoring gallery entry and applies [MATCH_THRESHOLD].
 */
object FaceMatcher {

    /** Cosine cut-off below which a match is treated as "no match" — same value `standalone_face_tf.py` uses. */
    const val MATCH_THRESHOLD = 0.38f

    /**
     * L2-normalizes [vec].
     *
     * @param vec Any-length float vector.
     * @return A new vector with unit L2 norm (a tiny epsilon avoids divide-by-zero on an all-zero input).
     */
    fun normalize(vec: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq) + 1e-9f
        return FloatArray(vec.size) { vec[it] / norm }
    }

    /**
     * Cosine similarity between [a] and [b] after normalizing both.
     *
     * @param a First vector.
     * @param b Second vector, same length as [a].
     * @return A value in [-1, 1]; 1 means identical direction.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val na = normalize(a)
        val nb = normalize(b)
        var dot = 0f
        for (i in na.indices) dot += na[i] * nb[i]
        return dot
    }

    /**
     * Finds the best-matching gallery entry for [probe].
     *
     * @param probe A single embedding to identify (e.g. one live capture during verify).
     * @param gallery Every enrolled embedding to compare against.
     * @return The best-scoring entry's `faceProfileId` if its score clears [MATCH_THRESHOLD],
     * else null; [MatchResult.bestScore] is always the raw best score found (0 if the gallery
     * is empty).
     *
     * Example Usage:
     * val result = FaceMatcher.identify(probeEmbedding, galleryEntries)
     * if (result.faceProfileId != null) { / * matched * / }
     */
    fun identify(probe: FloatArray, gallery: List<GalleryEntry>): MatchResult {
        if (gallery.isEmpty()) return MatchResult(null, 0f)

        var bestId: Long? = null
        var bestScore = -1f
        for (entry in gallery) {
            val score = cosine(probe, entry.vec)
            if (score > bestScore) {
                bestScore = score
                bestId = entry.faceProfileId
            }
        }
        return if (bestScore >= MATCH_THRESHOLD) MatchResult(bestId, bestScore) else MatchResult(null, bestScore.coerceAtLeast(0f))
    }
}
