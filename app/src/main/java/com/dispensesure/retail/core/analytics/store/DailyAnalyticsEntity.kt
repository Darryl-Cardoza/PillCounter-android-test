package com.dispensesure.retail.core.analytics.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per (pharmacyUserId, terminalName, operatorId, businessDate, region) per calendar day.
 *
 * Counters accumulate throughout the day. When the day is over the row is POSTed and, on 2xx,
 * deleted. A row that exists on the device has never been accepted by the server.
 *
 * Duration columns:
 * - `*DurationMin` defaults to -1 (sentinel = no sample yet). Encodes to 0 on the wire.
 * - `*DurationSamples` is stored but never sent — the server has no `samples` field and computes
 *   averages as `sum / count`, so a count whose duration was discarded (process death, over the
 *   4-hour ceiling) moves `count` without moving `sum`. This column exists to diagnose exactly
 *   that divergence.
 *
 * Collected but deliberately never sent: `accuracyCountMismatch`, `accuracyManualEntry` and
 * `safetyExpiredBlocked`. The upload contract is strict — unknown keys are rejected — and it has
 * no field for them. They stay on the row so the data exists if the contract gains them back.
 * See `AnalyticsDailyPayload.kt`.
 */
@Entity(
    tableName = "daily_analytics",
    indices = [
        Index(
            value = ["pharmacyUserId", "terminalName", "operatorId", "businessDate", "region"],
            unique = true,
        ),
        Index(value = ["businessDate"]),
    ],
)
data class DailyAnalyticsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    // ── Business key (5 parts) ──────────────────────────────────────────────────
    val pharmacyUserId: String,
    val terminalName: String,
    val operatorId: String,
    val businessDate: String,
    val region: String,

    // ── Context ────────────────────────────────────────────────────────────────
    val appVersion: String,
    val timezoneOffsetMin: Int,

    // ── Bookkeeping (local only, never sent) ───────────────────────────────────
    val uploadAttempts: Int = 0,
    val nextAttemptAt: Long = 0L,

    // ── Dispense — normal ──────────────────────────────────────────────────────
    val dispenseNormalCount: Long = 0L,
    val dispenseNormalDurationSamples: Long = 0L,
    val dispenseNormalDurationSum: Long = 0L,
    val dispenseNormalDurationMin: Long = -1L,
    val dispenseNormalDurationMax: Long = 0L,

    // ── Dispense — controlled ─────────────────────────────────────────────────
    val dispenseControlledCount: Long = 0L,
    val dispenseControlledDurationSamples: Long = 0L,
    val dispenseControlledDurationSum: Long = 0L,
    val dispenseControlledDurationMin: Long = -1L,
    val dispenseControlledDurationMax: Long = 0L,

    // ── Dispense — hazardous ──────────────────────────────────────────────────
    val dispenseHazardousCount: Long = 0L,
    val dispenseHazardousDurationSamples: Long = 0L,
    val dispenseHazardousDurationSum: Long = 0L,
    val dispenseHazardousDurationMin: Long = -1L,
    val dispenseHazardousDurationMax: Long = 0L,

    // ── Inventory — cycle count ────────────────────────────────────────────────
    val inventoryCycleCount: Long = 0L,
    val inventoryCycleDurationSamples: Long = 0L,
    val inventoryCycleDurationSum: Long = 0L,
    val inventoryCycleDurationMin: Long = -1L,
    val inventoryCycleDurationMax: Long = 0L,
    val inventoryCycleSealedBottlesScanned: Long = 0L,
    /** JSON array of distinct drug codes; see [AnalyticsNdcSet]. Null until the first scan. */
    val inventoryCycleNdcs: String? = null,

    // ── Inventory — stock count ────────────────────────────────────────────────
    val inventoryStockCount: Long = 0L,
    val inventoryStockDurationSamples: Long = 0L,
    val inventoryStockDurationSum: Long = 0L,
    val inventoryStockDurationMin: Long = -1L,
    val inventoryStockDurationMax: Long = 0L,
    val inventoryStockSealedBottlesScanned: Long = 0L,
    /** JSON array of distinct drug codes; see [AnalyticsNdcSet]. Null until the first scan. */
    val inventoryStockNdcs: String? = null,

    // ── Origin ─────────────────────────────────────────────────────────────────
    val originManualTransactions: Long = 0L,
    val originRxLabelProceeded: Long = 0L,
    val originOpenPill: Long = 0L,

    // ── Safety ─────────────────────────────────────────────────────────────────
    val safetyDrugCodeVerified: Long = 0L,
    val safetyWrongDrugBlocked: Long = 0L,
    val safetySubstituteFlagged: Long = 0L,
    val safetyExpiredBlocked: Long = 0L,

    // ── Expiry ─────────────────────────────────────────────────────────────────
    val expiryExpiredShown: Long = 0L,
    val expiryBeforeDosageEnd: Long = 0L,
    val expiryNearShown: Long = 0L,

    // ── Compliance ─────────────────────────────────────────────────────────────
    val complianceGlovesDetected: Long = 0L,
    val complianceGlovesNotDetected: Long = 0L,
    val complianceHazardousTrayDetected: Long = 0L,

    // ── Accuracy ───────────────────────────────────────────────────────────────
    val accuracyCountMismatch: Long = 0L,
    val accuracyVarianceAbsSum: Long = 0L,
    val accuracyBackcountPerformed: Long = 0L,
    val accuracyBackcountMismatch: Long = 0L,
    val accuracyRecountAttemptsSum: Long = 0L,
    val accuracyManualEntry: Long = 0L,
)
