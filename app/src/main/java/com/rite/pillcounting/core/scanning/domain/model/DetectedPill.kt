package com.rite.pillcounting.core.scanning.domain.model

/**
 * Represents a pill detected in a camera frame.
 *
 * @param x X-coordinate of the top-left corner or centroid.
 * @param y Y-coordinate of the top-left corner or centroid.
 * @param confidence Confidence score of the detection (0.0 - 1.0).
 */
data class DetectedPill(
    val x: Float,
    val y: Float,
    val confidence: Float
)