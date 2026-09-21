package com.rite.pillcounting.core.scanning.logic

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
 * One pill, one track. NMS (IoU 0.5) still lets a second box on the same pill
 * through when it overlaps 0.3–0.5 or is a small partial box (a class flip at
 * an edge), and a box that jumps between frames re-associates elsewhere while
 * its old track coasts. Both used to produce a second confirmed track on one
 * pill for a few frames — the count briefly reading one or two too many before
 * settling. So a leftover detection that lands on a pill whose track was matched
 * this frame never starts a track, and an unmatched track lying on a pill
 * another track claimed this frame is dropped instead of coasting.
 *
 * Camera motion. The phone is hand-held, so a pan moves every pill in frame
 * coordinates at once. Past the association IoU every track missed while every
 * detection spawned a new one, and the count read roughly double until the old
 * tracks died — the "more pills when I move the camera" symptom the fixed-camera
 * desktop demo never shows. Before association each frame, every track is
 * shifted by the camera motion: the image-registration estimate the analyzer
 * passes in ([CameraMotionEstimator]), or, when that is unavailable, the median
 * displacement between detections and their nearest track. A pill the user
 * moves is an outlier to that median and still gets its own new track.
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
        // Two boxes are the same pill when the smaller is mostly inside the
        // larger (partial box on a whole pill). Distinct pills that touch
        // overlap far less than this; only a pill stacked on another comes close.
        const val DUPLICATE_CONTAINMENT = 0.70f
        // Camera-motion estimate: a detection votes with its displacement from
        // the nearest track within this many median pill sides, and at least
        // MIN_SHIFT_VOTES votes are needed before any shift is applied.
        const val SHIFT_SEARCH_RADIUS_PILLS = 2f
        const val MIN_SHIFT_VOTES = 3
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
     *
     * @param cameraShift  How far the scene moved since the last frame, in the
     *                     detections' coordinate space, from image registration.
     *                     Null falls back to a vote over the detections.
     */
    fun update(detections: List<Detection>, cameraShift: PointF? = null): List<Detection> {
        // Snapshot the existing tracks: one created this frame must not be
        // available for association until the next frame.
        val existingCount = tracks.size
        val shift = cameraShift?.let { it.x to it.y } ?: shiftFromDetections(detections)
        shift?.let { (dx, dy) -> for (t in tracks) t.rect.offset(dx, dy) }
        val matched = BooleanArray(existingCount)
        val spawned = mutableListOf<Track>()
        val leftovers = mutableListOf<Detection>()

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
                        track.rect = RectF(det.rect)
                        track.confidence = det.confidence
                        track.classId = det.classId
                        track.missedFrames = 0
                    }
                    // Candidate: only a match at or above enter score extends the streak.
                    det.confidence >= ENTER_SCORE -> {
                        track.hitStreak++
                        track.rect = RectF(det.rect)
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
                leftovers.add(det)
            }
        }

        // Leftovers are in confidence order. One starts a track only if it lies
        // on no pill already accounted for this frame — neither a track matched
        // above nor a track spawned from a stronger leftover.
        for (det in leftovers) {
            val onTrackedPill = (0 until existingCount).any { matched[it] && samePill(det.rect, tracks[it].rect) } ||
                spawned.any { samePill(det.rect, it.rect) }
            if (onTrackedPill) continue
            spawned.add(
                Track(
                    rect = RectF(det.rect),
                    confidence = det.confidence,
                    classId = det.classId,
                    hitStreak = 1,
                    confirmed = false
                )
            )
        }

        for (i in 0 until existingCount) {
            if (matched[i]) continue
            val track = tracks[i]
            // An unmatched track lying on a pill that a matched track now covers
            // is a ghost — the pill's box moved and re-associated, or a duplicate
            // box vanished. Retire it now instead of counting it while it coasts.
            val ghost = (0 until existingCount).any { matched[it] && samePill(track.rect, tracks[it].rect) }
            track.missedFrames = if (ghost) EXIT_UNMATCHED_FRAMES else track.missedFrames + 1
            // "2 consecutive frames" means consecutive — a miss breaks the streak.
            if (!track.confirmed) track.hitStreak = 0
        }
        tracks.removeAll { it.missedFrames >= EXIT_UNMATCHED_FRAMES }
        tracks.addAll(spawned)

        return tracks
            .filter { it.confirmed }
            .map { Detection(rect = RectF(it.rect), confidence = it.confidence, classId = it.classId) }
    }

    /** Drops all tracks so the next frame starts confirmation from scratch. */
    fun reset() {
        tracks.clear()
    }

    /**
     * Fallback camera-motion estimate: median displacement from each detection
     * to its nearest existing track, or null when too few detections have a
     * track near enough to vote. Reliable while the shift stays under half the
     * pill spacing; beyond that only image registration can tell.
     */
    private fun shiftFromDetections(detections: List<Detection>): Pair<Float, Float>? {
        if (tracks.isEmpty() || detections.size < MIN_SHIFT_VOTES) return null
        val radius = SHIFT_SEARCH_RADIUS_PILLS * median(detections.map { sqrt(it.rect.width() * it.rect.height()) })
        val dxs = ArrayList<Float>(detections.size)
        val dys = ArrayList<Float>(detections.size)
        for (det in detections) {
            if (det.confidence < KEEP_SCORE) continue
            val cx = det.rect.centerX()
            val cy = det.rect.centerY()
            var nearest: Track? = null
            var nearestDist = Float.MAX_VALUE
            for (t in tracks) {
                val d = hypot(t.rect.centerX() - cx, t.rect.centerY() - cy)
                if (d < nearestDist) {
                    nearestDist = d
                    nearest = t
                }
            }
            val n = nearest ?: continue
            if (nearestDist > radius) continue
            dxs.add(cx - n.rect.centerX())
            dys.add(cy - n.rect.centerY())
        }
        if (dxs.size < MIN_SHIFT_VOTES) return null
        return median(dxs) to median(dys)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun intersection(a: RectF, b: RectF): Float {
        val width = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val height = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        return width * height
    }

    private fun iou(a: RectF, b: RectF): Float {
        val intersection = intersection(a, b)
        if (intersection <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 0f) intersection / union else 0f
    }

    /**
     * Two boxes describe the same pill when they overlap at the association
     * threshold, or when the smaller box is mostly inside the larger one.
     */
    private fun samePill(a: RectF, b: RectF): Boolean {
        val intersection = intersection(a, b)
        if (intersection <= 0f) return false
        if (iou(a, b) >= TRACK_IOU) return true
        val smaller = min(a.width() * a.height(), b.width() * b.height())
        return smaller > 0f && intersection / smaller >= DUPLICATE_CONTAINMENT
    }
}
