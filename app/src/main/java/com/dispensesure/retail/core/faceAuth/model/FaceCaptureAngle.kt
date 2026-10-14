package com.dispensesure.retail.core.faceAuth.model

/**
 * The 3 directed capture poses used during face registration.
 *
 * Description:
 * Matches the Figma "Scan Face" onboarding steps (front, tilt-left, tilt-right).
 * One [com.dispensesure.retail.core.room.models.FaceEmbeddingEntity] is stored per angle.
 */
enum class FaceCaptureAngle { FRONT, TILT_LEFT, TILT_RIGHT }
