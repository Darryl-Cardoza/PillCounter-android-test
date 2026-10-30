package com.dispensesure.retail.core.analytics.upload

import com.dispensesure.retail.core.analytics.collect.PmsLifecycle
import com.dispensesure.retail.core.analytics.store.DailyAnalyticsEntity
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Request ──────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AnalyticsDailyRequest(
    @Json(name = "device_key") val deviceKey: String,
    @Json(name = "records") val records: List<AnalyticsDailyRecord>,
)

@JsonClass(generateAdapter = true)
data class AnalyticsDailyReply(
    @Json(name = "message") val message: String? = null,
)

// ── Record (one per daily_analytics row) ─────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AnalyticsDailyRecord(
    @Json(name = "business_date") val businessDate: String,
    @Json(name = "revision") val revision: Int,
    @Json(name = "terminal_name") val terminalName: String,
    @Json(name = "operator_id") val operatorId: String,
    @Json(name = "context") val context: AnalyticsContextBlock,
    @Json(name = "volume") val volume: AnalyticsVolumeBlock,
    @Json(name = "origin") val origin: AnalyticsOriginBlock,
    @Json(name = "pms") val pms: AnalyticsPmsBlock,
    @Json(name = "safety") val safety: AnalyticsSafetyBlock,
    @Json(name = "expiry") val expiry: AnalyticsExpiryBlock,
    @Json(name = "compliance") val compliance: AnalyticsComplianceBlock,
    @Json(name = "accuracy") val accuracy: AnalyticsAccuracyBlock,
)

// ── Nested blocks ─────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AnalyticsContextBlock(
    @Json(name = "region") val region: String,
    @Json(name = "timezone_offset_min") val timezoneOffsetMin: Int,
    @Json(name = "app_version") val appVersion: String,
)

@JsonClass(generateAdapter = true)
data class AnalyticsVolumeBlock(
    @Json(name = "dispense") val dispense: AnalyticsDispenseBlock,
    @Json(name = "inventory") val inventory: AnalyticsInventoryBlock,
)

@JsonClass(generateAdapter = true)
data class AnalyticsDispenseBlock(
    @Json(name = "normal") val normal: AnalyticsWorkTypeBlock,
    @Json(name = "controlled") val controlled: AnalyticsWorkTypeBlock,
    @Json(name = "hazardous") val hazardous: AnalyticsWorkTypeBlock,
)

@JsonClass(generateAdapter = true)
data class AnalyticsInventoryBlock(
    @Json(name = "cycle_count") val cycleCount: AnalyticsInventoryWorkTypeBlock,
    @Json(name = "stock_count") val stockCount: AnalyticsInventoryWorkTypeBlock,
)

@JsonClass(generateAdapter = true)
data class AnalyticsWorkTypeBlock(
    @Json(name = "count") val count: Long,
    @Json(name = "duration_seconds") val durationSeconds: AnalyticsDurationBlock,
)

@JsonClass(generateAdapter = true)
data class AnalyticsInventoryWorkTypeBlock(
    @Json(name = "count") val count: Long,
    @Json(name = "duration_seconds") val durationSeconds: AnalyticsDurationBlock,
    @Json(name = "sealed_bottles_scanned") val sealedBottlesScanned: Long,
    @Json(name = "ndc_scanned") val ndcScanned: Long,
)

@JsonClass(generateAdapter = true)
data class AnalyticsDurationBlock(
    @Json(name = "sum") val sum: Long,
    @Json(name = "min") val min: Long,
    @Json(name = "max") val max: Long,
)

@JsonClass(generateAdapter = true)
data class AnalyticsOriginBlock(
    @Json(name = "manual_transactions") val manualTransactions: Long,
    @Json(name = "pms_transactions") val pmsTransactions: Long,
    @Json(name = "rx_label_proceeded") val rxLabelProceeded: Long,
    @Json(name = "open_pill") val openPill: Long,
)

@JsonClass(generateAdapter = true)
data class AnalyticsSafetyBlock(
    @Json(name = "drug_code_verified") val drugCodeVerified: Long,
    @Json(name = "wrong_drug_blocked") val wrongDrugBlocked: Long,
    @Json(name = "substitute_flagged") val substituteFlagged: Long,
)

@JsonClass(generateAdapter = true)
data class AnalyticsExpiryBlock(
    @Json(name = "expired_shown") val expiredShown: Long,
    @Json(name = "before_dosage_end") val beforeDosageEnd: Long,
    @Json(name = "near_shown") val nearShown: Long,
)

@JsonClass(generateAdapter = true)
data class AnalyticsComplianceBlock(
    @Json(name = "gloves_detected") val glovesDetected: Long,
    @Json(name = "gloves_not_detected") val glovesNotDetected: Long,
    @Json(name = "hazardous_tray_detected") val hazardousTrayDetected: Long,
)

@JsonClass(generateAdapter = true)
data class AnalyticsAccuracyBlock(
    @Json(name = "variance_abs_sum") val varianceAbsSum: Long,
    @Json(name = "backcount_performed") val backcountPerformed: Long,
    @Json(name = "backcount_mismatch") val backcountMismatch: Long,
    @Json(name = "recount_attempts_sum") val recountAttemptsSum: Long,
)

@JsonClass(generateAdapter = true)
data class AnalyticsPmsBlock(
    @Json(name = "dispense") val dispense: AnalyticsPmsLifecycleBlock,
    @Json(name = "inventory") val inventory: AnalyticsPmsLifecycleBlock,
)

@JsonClass(generateAdapter = true)
data class AnalyticsPmsLifecycleBlock(
    @Json(name = "received") val received: Long,
    @Json(name = "completed") val completed: Long,
    @Json(name = "synced") val synced: Long,
    @Json(name = "pending_at_day_end") val pendingAtDayEnd: Long,
)

// ── Mapping ───────────────────────────────────────────────────────────────────

fun DailyAnalyticsEntity.toRecord(pms: PmsLifecycle): AnalyticsDailyRecord {
    val totalDispenseVolume = dispenseNormalCount + dispenseControlledCount + dispenseHazardousCount
    val pmsTransactions = maxOf(0L, totalDispenseVolume - originManualTransactions)

    return AnalyticsDailyRecord(
        businessDate = businessDate,
        revision = 0,
        terminalName = terminalName,
        operatorId = operatorId,
        context = AnalyticsContextBlock(
            region = region,
            timezoneOffsetMin = timezoneOffsetMin,
            appVersion = appVersion,
        ),
        volume = AnalyticsVolumeBlock(
            dispense = AnalyticsDispenseBlock(
                normal = AnalyticsWorkTypeBlock(
                    count = dispenseNormalCount,
                    durationSeconds = AnalyticsDurationBlock(
                        sum = dispenseNormalDurationSum,
                        min = if (dispenseNormalDurationMin < 0) 0L else dispenseNormalDurationMin,
                        max = dispenseNormalDurationMax,
                    ),
                ),
                controlled = AnalyticsWorkTypeBlock(
                    count = dispenseControlledCount,
                    durationSeconds = AnalyticsDurationBlock(
                        sum = dispenseControlledDurationSum,
                        min = if (dispenseControlledDurationMin < 0) 0L else dispenseControlledDurationMin,
                        max = dispenseControlledDurationMax,
                    ),
                ),
                hazardous = AnalyticsWorkTypeBlock(
                    count = dispenseHazardousCount,
                    durationSeconds = AnalyticsDurationBlock(
                        sum = dispenseHazardousDurationSum,
                        min = if (dispenseHazardousDurationMin < 0) 0L else dispenseHazardousDurationMin,
                        max = dispenseHazardousDurationMax,
                    ),
                ),
            ),
            inventory = AnalyticsInventoryBlock(
                cycleCount = AnalyticsInventoryWorkTypeBlock(
                    count = inventoryCycleCount,
                    durationSeconds = AnalyticsDurationBlock(
                        sum = inventoryCycleDurationSum,
                        min = if (inventoryCycleDurationMin < 0) 0L else inventoryCycleDurationMin,
                        max = inventoryCycleDurationMax,
                    ),
                    sealedBottlesScanned = inventoryCycleSealedBottlesScanned,
                    ndcScanned = inventoryCycleNdcCount,
                ),
                stockCount = AnalyticsInventoryWorkTypeBlock(
                    count = inventoryStockCount,
                    durationSeconds = AnalyticsDurationBlock(
                        sum = inventoryStockDurationSum,
                        min = if (inventoryStockDurationMin < 0) 0L else inventoryStockDurationMin,
                        max = inventoryStockDurationMax,
                    ),
                    sealedBottlesScanned = inventoryStockSealedBottlesScanned,
                    ndcScanned = inventoryStockNdcCount,
                ),
            ),
        ),
        origin = AnalyticsOriginBlock(
            manualTransactions = originManualTransactions,
            pmsTransactions = pmsTransactions,
            rxLabelProceeded = originRxLabelProceeded,
            openPill = originOpenPill,
        ),
        safety = AnalyticsSafetyBlock(
            drugCodeVerified = safetyDrugCodeVerified,
            wrongDrugBlocked = safetyWrongDrugBlocked,
            substituteFlagged = safetySubstituteFlagged,
        ),
        expiry = AnalyticsExpiryBlock(
            expiredShown = expiryExpiredShown,
            beforeDosageEnd = expiryBeforeDosageEnd,
            nearShown = expiryNearShown,
        ),
        compliance = AnalyticsComplianceBlock(
            glovesDetected = complianceGlovesDetected,
            glovesNotDetected = complianceGlovesNotDetected,
            hazardousTrayDetected = complianceHazardousTrayDetected,
        ),
        accuracy = AnalyticsAccuracyBlock(
            varianceAbsSum = accuracyVarianceAbsSum,
            backcountPerformed = accuracyBackcountPerformed,
            backcountMismatch = accuracyBackcountMismatch,
            recountAttemptsSum = accuracyRecountAttemptsSum,
        ),
        pms = AnalyticsPmsBlock(
            dispense = AnalyticsPmsLifecycleBlock(
                received = pms.dispense.received,
                completed = pms.dispense.completed,
                synced = pms.dispense.synced,
                pendingAtDayEnd = pms.dispense.pendingAtDayEnd,
            ),
            inventory = AnalyticsPmsLifecycleBlock(
                received = pms.inventory.received,
                completed = pms.inventory.completed,
                synced = pms.inventory.synced,
                pendingAtDayEnd = pms.inventory.pendingAtDayEnd,
            ),
        ),
    )
}
