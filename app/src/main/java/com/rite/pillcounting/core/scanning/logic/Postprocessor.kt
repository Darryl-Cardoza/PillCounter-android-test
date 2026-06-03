package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF

object Postprocessor {

    /**
     * Decode YOLO-style pill output of shape `[5, N]` (channel-first):
     * row 0=cx, 1=cy, 2=w, 3=h, 4=conf. Coordinates are emitted in 640-pixel space
     * (not normalised), so no scaling-by-640 happens here. Reverse-letterbox to
     * the original camera frame using the supplied scale/pad values.
     */
    fun decode(
        raw: Array<FloatArray>,
        confThreshold: Float,
        scale: Float,
        padX: Float,
        padY: Float
    ): List<Detection> {

        val cxs = raw[0]
        val cys = raw[1]
        val ws  = raw[2]
        val hs  = raw[3]
        val confs = raw[4]
        val n = cxs.size

        val results = ArrayList<Detection>(64)

        for (i in 0 until n) {
            val score = confs[i]
            if (score < confThreshold) continue

            val cx = cxs[i]
            val cy = cys[i]
            val w  = ws[i]
            val h  = hs[i]

            val halfW = w / 2f
            val halfH = h / 2f

            val x1 = (cx - halfW - padX) / scale
            val y1 = (cy - halfH - padY) / scale
            val x2 = (cx + halfW - padX) / scale
            val y2 = (cy + halfH - padY) / scale

            results.add(Detection(rect = RectF(x1, y1, x2, y2), confidence = score))
        }

        return results
    }
}