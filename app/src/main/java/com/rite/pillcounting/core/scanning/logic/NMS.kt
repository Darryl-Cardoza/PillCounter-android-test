package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

object NMS {

    fun run(
        detections: List<Detection>,
        iouThreshold: Float,
        nmsTopK: Int = 1500,
        keepTopK: Int = 500
    ): List<Detection> {

        // Sort by confidence DESC (same as iOS), then cap candidates. The greedy
        // loop below is O(n^2) and a noisy frame can push thousands of anchors in.
        val sorted = detections.sortedByDescending { it.confidence }.take(nmsTopK)
        val keep = mutableListOf<Detection>()

        for (det in sorted) {
            if (keep.size >= keepTopK) break

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