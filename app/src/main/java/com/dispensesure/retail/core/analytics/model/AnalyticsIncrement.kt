
package com.dispensesure.retail.core.analytics.model

/**
 * A single counter delta to apply to one row of [com.dispensesure.retail.core.analytics.store.DailyAnalyticsEntity].
 *
 * @property field The column to increment.
 * @property delta Amount to add — always positive in normal operation.
 */
data class AnalyticsIncrement(
    val field: AnalyticsField,
    val delta: Long,
)

/**
 * A derived duration to fold into a work type's duration aggregate columns.
 *
 * @property workType The bucket this duration belongs to.
 * @property seconds Elapsed seconds — positive, already ceiling-clamped and validated by the caller.
 */
data class AnalyticsDuration(
    val workType: AnalyticsWorkType,
    val seconds: Long,
)

/**
 * Every counter column that [com.dispensesure.retail.core.analytics.store.AnalyticsStore]
 * can increment atomically.
 *
 * Using an enum instead of a raw column name means a typo is a compile error, not a silent no-op.
 */
enum class AnalyticsField {
    // Dispense — normal
    DISPENSE_NORMAL_COUNT,
    DISPENSE_NORMAL_DURATION_SAMPLES,
    DISPENSE_NORMAL_DURATION_SUM,
    // Dispense — controlled
    DISPENSE_CONTROLLED_COUNT,
    DISPENSE_CONTROLLED_DURATION_SAMPLES,
    DISPENSE_CONTROLLED_DURATION_SUM,
    // Dispense — hazardous
    DISPENSE_HAZARDOUS_COUNT,
    DISPENSE_HAZARDOUS_DURATION_SAMPLES,
    DISPENSE_HAZARDOUS_DURATION_SUM,
    // Inventory — cycle count
    INVENTORY_CYCLE_COUNT,
    INVENTORY_CYCLE_DURATION_SAMPLES,
    INVENTORY_CYCLE_DURATION_SUM,
    INVENTORY_CYCLE_SEALED_BOTTLES_SCANNED,
    INVENTORY_CYCLE_NDC_COUNT,
    // Inventory — stock count
    INVENTORY_STOCK_COUNT,
    INVENTORY_STOCK_DURATION_SAMPLES,
    INVENTORY_STOCK_DURATION_SUM,
    INVENTORY_STOCK_SEALED_BOTTLES_SCANNED,
    INVENTORY_STOCK_NDC_COUNT,
    // Origin
    ORIGIN_MANUAL_TRANSACTIONS,
    ORIGIN_RX_LABEL_PROCEEDED,
    ORIGIN_OPEN_PILL,
    // Safety
    SAFETY_DRUG_CODE_VERIFIED,
    SAFETY_WRONG_DRUG_BLOCKED,
    SAFETY_SUBSTITUTE_FLAGGED,
    SAFETY_EXPIRED_BLOCKED,
    // Expiry
    EXPIRY_EXPIRED_SHOWN,
    EXPIRY_BEFORE_DOSAGE_END,
    EXPIRY_NEAR_SHOWN,
    // Compliance
    COMPLIANCE_GLOVES_DETECTED,
    COMPLIANCE_GLOVES_NOT_DETECTED,
    COMPLIANCE_HAZARDOUS_TRAY_DETECTED,
    // Accuracy
    ACCURACY_COUNT_MISMATCH,
    ACCURACY_VARIANCE_ABS_SUM,
    ACCURACY_BACKCOUNT_PERFORMED,
    ACCURACY_BACKCOUNT_MISMATCH,
    ACCURACY_RECOUNT_ATTEMPTS_SUM,
    ACCURACY_MANUAL_ENTRY,
}
