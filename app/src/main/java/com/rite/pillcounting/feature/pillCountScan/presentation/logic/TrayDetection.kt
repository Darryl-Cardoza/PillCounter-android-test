package com.rite.pillcounting.feature.pillCountScan.presentation.logic

import android.graphics.RectF
import java.util.BitSet

/**
 * Two foreground classes the tray segmentation model recognizes. Background
 * is implicit (the absence of a [TrayDetection] for a given pixel).
 */
enum class TrayClass { TRAY, CHUTE }

/**
 * One detected tray or chute region. The shape of this type is shared
 * between the (now-deprecated) RTMDet-Ins instance-segmentation detector
 * and the current MobileNetV2-UNet semantic-segmentation detector
 * ([TraySegmentationDetector]) so that PillAnalyzer's "is this pill in any
 * tray and outside every chute?" gating logic is unchanged.
 *
 * Mask is stored as a packed [BitSet] in row-major (maskSize × maskSize)
 * order — one allocation per detection (a few KB at 384, ~50 KB at 640)
 * instead of N BooleanArray allocations. Lookups are O(1) via a single
 * index.
 *
 * The [scaleInfo] field describes the mapping from original-image
 * coordinates to mask-space coordinates ([containsPoint] uses it to
 * project a pill centre into the mask). It is calibrated to whatever
 * mask space the detector produces (384 for the current semantic-seg
 * detector, 640 for the legacy detector).
 */
data class TrayDetection(
    val rect: RectF,
    val confidence: Float,
    val cls: TrayClass = TrayClass.TRAY,
    val mask: BitSet? = null,
    val maskSize: Int = 0,
    val scaleInfo: Letterbox.ScaleInfo? = null
) {
    fun containsPoint(x: Int, y: Int): Boolean {
        val m = mask
        val s = scaleInfo
        if (m != null && s != null && maskSize > 0) {
            val xMask = (x.toFloat() * s.scale + s.padX).toInt()
            val yMask = (y.toFloat() * s.scale + s.padY).toInt()
            if (xMask < 0 || xMask >= maskSize || yMask < 0 || yMask >= maskSize) return false
            return m.get(yMask * maskSize + xMask)
        }
        return x.toFloat() in rect.left..rect.right && y.toFloat() in rect.top..rect.bottom
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrayDetection) return false
        return confidence == other.confidence && rect == other.rect && cls == other.cls
    }
    override fun hashCode(): Int =
        31 * (31 * rect.hashCode() + confidence.hashCode()) + cls.hashCode()
}
