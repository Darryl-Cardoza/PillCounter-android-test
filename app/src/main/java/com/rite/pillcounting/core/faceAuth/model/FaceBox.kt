package com.rite.pillcounting.core.faceAuth.model

import android.graphics.RectF

/**
 * One face detected by [com.rite.pillcounting.core.faceAuth.logic.YuNetDecoder].
 *
 * Description:
 * Direct Kotlin equivalent of `standalone_face_tf.py`'s `Face` dataclass.
 *
 * @param rect Bounding box in the original (undistorted) frame's pixel coordinates.
 * @param landmarks 10 floats: right-eye(x,y), left-eye(x,y), nose(x,y), right-mouth(x,y), left-mouth(x,y).
 * @param score Detector confidence, 0..1.
 */
data class FaceBox(
    val rect: RectF,
    val landmarks: FloatArray,
    val score: Float
)
