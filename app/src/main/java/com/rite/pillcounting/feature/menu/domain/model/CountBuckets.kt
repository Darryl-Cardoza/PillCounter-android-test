package com.rite.pillcounting.feature.menu.domain.model

/**
 * Container for transaction count buckets.
 *
 * Holds segregated counts for:
 * - Fixed Completed
 * - Fixed Partial
 * - Regular Completed
 * - Regular Partial
 */
data class CountBuckets(
    val fixedCompleted: Int = 0,
    val fixedPartial: Int = 0,
    val regularCompleted: Int = 0,
    val regularPartial: Int = 0
)
