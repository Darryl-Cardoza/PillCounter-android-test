package com.rite.pillcounting.core.scanning.logic

import android.graphics.PointF
import com.rite.pillcounting.core.utils.logger.AppLogger

object CentroidMapper {

    private val logger = AppLogger("CentroidMapper")

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

        logger.i(
            """
            [CentroidMapper]
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

        logger.i(
            """
            [CentroidMapper]
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