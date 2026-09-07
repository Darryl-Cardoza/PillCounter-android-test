package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Per-track state machine over the post-NMS pill detections.
 *
 * Replaces the previous single-frame hysteresis: a track must be seen on
 * [ENTER_FRAMES] consecutive frames at or above [ENTER_SCORE] before it is
 * confirmed (and counted), stays alive while it keeps matching at or above
 * [KEEP_SCORE], and only exits after [EXIT_UNMATCHED_FRAMES] consecutive misses.
 * Confirmed tracks are still returned while coasting through those misses —
 * that grace is what stops a one-frame drop from moving the count.
 *
 * Values come from the shipped desktop reference config (version 5).
 */
class PillTracker {

    companion object {
        const val TRACK_IOU = 0.30f
        const val ENTER_SCORE = 0.50f
        const val ENTER_FRAMES = 2
        const val KEEP_SCORE = 0.35f
        const val EXIT_UNMATCHED_FRAMES = 3
    }

    private class Track(
        var rect: RectF,
        var confidence: Float,
        var classId: Int,
        var hitStreak: Int,
        var missedFrames: Int = 0,
        var confirmed: Boolean = false
    )

    private val tracks = mutableListOf<Track>()

    /**
     * Advances every track by one frame and returns the confirmed ones as
     * detections in the same coordinate space the input used.
     */
    fun update(detections: List<Detection>): List<Detection> {
        // Snapshot the existing tracks: one created this frame must not be
        // available for association until the next frame.
        val existingCount = tracks.size
        val matched = BooleanArray(existingCount)
        val spawned = mutableListOf<Track>()

        for (det in detections.sortedByDescending { it.confidence }) {
            if (det.confidence < KEEP_SCORE) continue

            var bestIdx = -1
            var bestIou = -1f
            for (i in 0 until existingCount) {
                if (matched[i]) continue
                val overlap = iou(det.rect, tracks[i].rect)
                if (overlap >= TRACK_IOU && overlap > bestIou) {
                    bestIou = overlap
                    bestIdx = i
                }
            }

            if (bestIdx >= 0) {
                matched[bestIdx] = true
                val track = tracks[bestIdx]
                when {
                    // Confirmed: any match at or above keep score refreshes it.
                    track.confirmed -> {
                        track.rect = det.rect
                        track.confidence = det.confidence
                        track.classId = det.classId
                        track.missedFrames = 0
                    }
                    // Candidate: only a match at or above enter score extends the streak.
                    det.confidence >= ENTER_SCORE -> {
                        track.hitStreak++
                        track.rect = det.rect
                        track.confidence = det.confidence
                        track.classId = det.classId
                        track.missedFrames = 0
                        if (track.hitStreak >= ENTER_FRAMES) track.confirmed = true
                    }
                    // Candidate matched below enter score: the streak breaks and the
                    // track ages toward exit rather than living on indefinitely.
                    else -> {
                        track.hitStreak = 0
                        track.missedFrames++
                    }
                }
            } else if (det.confidence >= ENTER_SCORE) {
                // A track only ever starts at enter score — a borderline detection
                // that matches nothing is not evidence of a new pill.
                spawned.add(
                    Track(
                        rect = det.rect,
                        confidence = det.confidence,
                        classId = det.classId,
                        hitStreak = 1,
                        confirmed = ENTER_FRAMES <= 1
                    )
                )
            }
        }

        for (i in 0 until existingCount) {
            if (!matched[i]) {
                tracks[i].missedFrames++
                // "2 consecutive frames" means consecutive — a miss breaks the streak.
                if (!tracks[i].confirmed) tracks[i].hitStreak = 0
            }
        }
        tracks.removeAll { it.missedFrames >= EXIT_UNMATCHED_FRAMES }
        tracks.addAll(spawned)

        return tracks
            .filter { it.confirmed }
            .map { Detection(rect = it.rect, confidence = it.confidence, classId = it.classId) }
    }

    /** Drops all tracks so the next frame starts confirmation from scratch. */
    fun reset() {
        tracks.clear()
    }

    private fun iou(a: RectF, b: RectF): Float {
        val width = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val height = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        val intersection = width * height
        if (intersection <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 0f) intersection / union else 0f
    }
}
