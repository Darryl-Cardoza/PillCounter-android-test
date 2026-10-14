package com.dispensesure.retail.feature.menu.domain.model

/**
 * Container for transaction count buckets.
 *
 * Holds segregated counts for:
 * - Fixed Completed
 * - Fixed Partial
 */
data class CountBuckets(
    val fixedCompleted: Int = 0,
    val fixedPartial: Int = 0
)
