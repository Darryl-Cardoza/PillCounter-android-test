package com.rite.pillcounting.feature.dispenseFlow.presentation.logic

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

object NMS {

    fun run(
        detections: List<Detection>,
        iouThreshold: Float
    ): List<Detection> {

        // Sort by confidence DESC (same as iOS)
        val sorted = detections.sortedByDescending { it.confidence }
        val keep = mutableListOf<Detection>()

        for (det in sorted) {
            var shouldKeep = true

            for (kept in keep) {
                if (iou(det.rect, kept.rect) > iouThreshold) {
                    shouldKeep = false
                    break
                }
            }

            if (shouldKeep) {
                keep.add(det)
            }
        }

        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val intersectionLeft = max(a.left, b.left)
        val intersectionTop = max(a.top, b.top)
        val intersectionRight = min(a.right, b.right)
        val intersectionBottom = min(a.bottom, b.bottom)

        val intersectionWidth = intersectionRight - intersectionLeft
        val intersectionHeight = intersectionBottom - intersectionTop

        if (intersectionWidth <= 0f || intersectionHeight <= 0f) return 0f

        val intersectionArea = intersectionWidth * intersectionHeight
        val unionArea = a.area() + b.area() - intersectionArea

        return intersectionArea / unionArea
    }

    private fun RectF.area(): Float {
        return width() * height()
    }
}