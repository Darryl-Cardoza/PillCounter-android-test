package com.rite.pillcounting.feature.dispenseFlow.presentation.logic

import android.graphics.PointF
import android.util.Log

object CentroidMapper {

    private const val TAG = "PillAnalyzer"

    fun toPreview(
        detection: Detection,
        imageWidth: Int,
        imageHeight: Int,
        previewWidth: Int,
        previewHeight: Int
    ): PointF {

        // ------------------------------------
        // IMAGE SPACE (after reverse letterbox)
        // ------------------------------------
        val cxImage = detection.rect.centerX()
        val cyImage = detection.rect.centerY()

        Log.i(
            TAG,
            """
            📍 [CentroidMapper]
            IMAGE SPACE
            imageSize   = ${imageWidth}x${imageHeight}
            centroidImg = (${cxImage.toInt()}, ${cyImage.toInt()})
            """.trimIndent()
        )

        // ------------------------------------
        // SCALE IMAGE → PREVIEW
        // ------------------------------------
        val scaleX = previewWidth.toFloat() / imageWidth
        val scaleY = previewHeight.toFloat() / imageHeight

        val cxPreview = cxImage * scaleX
        val cyPreview = cyImage * scaleY

        Log.i(
            TAG,
            """
            🖥️ [CentroidMapper]
            PREVIEW SPACE
            previewSize = ${previewWidth}x${previewHeight}
            scaleX      = $scaleX
            scaleY      = $scaleY
            centroidPre = (${cxPreview.toInt()}, ${cyPreview.toInt()})
            """.trimIndent()
        )

        return PointF(cxPreview, cyPreview)
    }
}