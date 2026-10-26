package com.dispensesure.retail.feature.menu.domain.model

/**
 * Container for transaction count buckets.
 *
 * Holds segregated counts for:
 * - Dispense Completed
 * - Dispense Partial
 */
data class CountBuckets(
    val dispenseCompleted: Int = 0,
    val dispensePartial: Int = 0
)
