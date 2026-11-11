package com.dispensesure.retail.core.analytics.collect

import com.dispensesure.retail.core.analytics.model.AnalyticsField
import com.dispensesure.retail.core.analytics.model.AnalyticsIncrement
import com.dispensesure.retail.core.analytics.model.AnalyticsSetInsertion
import com.dispensesure.retail.core.analytics.model.AnalyticsWorkType
import kotlin.math.abs
import kotlin.math.roundToLong

/** Value object filled by [AnalyticsObserver] from Room, passed to [AnalyticsFieldMapping]. */
data class DispenseAnalyticsInput(
    val workType: AnalyticsWorkType,
    val isHazardous: Boolean,
    val isComingFromHL7: Boolean,
    val isNdcVerified: Boolean,
    val isSubstitute: Boolean,
    val isHazardousDrugEnabled: Boolean,
    val isGlovesPresent: Boolean,
    val hazardousTrayDetected: Boolean?,
    val targetCount: Int?,
    val finalPillCount: Int?,
    val recountAttempts: Int,
    val manualEntries: Int,
    val backcountPerformed: Boolean,
    val backcountMismatch: Boolean,
    val createdAt: Long,
    val completedAt: Long,
)

/**
 * What one `StockBottleRecorded` event adds to the daily row.
 *
 * The quantities are **deltas**, not totals. A stock transaction keeps accumulating after its
 * first bottle line, so [AnalyticsObserver] re-reads its totals on each event and subtracts what
 * the ledger says was already counted. That reports bottles rather than lines — a sealed lot uses
 * fetch-or-create, so N scans of one lot produce a single line with `bottleQty = N` — and it also
 * picks up quantities edited after the first scan, which counting once never saw.
 *
 * @property sealedBottleQty Sealed bottles added since the last event. Negative when a line was
 *   deleted or revised downward; zero when this event changed no sealed quantity.
 * @property looseLineCount Loose-pill lines added since the last event — each is one opened
 *   container. Negative on deletion.
 * @property ndc The drug code, recorded into the work type's distinct-code set.
 * @property countTransaction True only on the transaction's first event, so the transaction itself
 *   is counted once however many lines it ends up with.
 */
data class StockAnalyticsInput(
    val workType: AnalyticsWorkType,
    val sealedBottleQty: Long,
    val looseLineCount: Long,
    val ndc: String?,
    val countTransaction: Boolean = true,
)

/**
 * Pure mapping functions — no Room, no Hilt. Every case in the design §5.3 and §5.4 table.
 */
object AnalyticsFieldMapping {

    fun dispenseIncrements(input: DispenseAnalyticsInput): List<AnalyticsIncrement> =
        buildList {
            val countField = when (input.workType) {
                AnalyticsWorkType.DISPENSE_NORMAL -> AnalyticsField.DISPENSE_NORMAL_COUNT
                AnalyticsWorkType.DISPENSE_CONTROLLED -> AnalyticsField.DISPENSE_CONTROLLED_COUNT
                else -> null
            }
            if (countField != null) add(AnalyticsIncrement(countField, 1L))
            // Hazardous is a cross-cutting tag: always increment hazardous counter in addition to
            // the primary (normal or controlled) counter when the drug is hazardous.
            if (input.isHazardous) add(AnalyticsIncrement(AnalyticsField.DISPENSE_HAZARDOUS_COUNT, 1L))

            if (!input.isComingFromHL7) add(AnalyticsIncrement(AnalyticsField.ORIGIN_MANUAL_TRANSACTIONS, 1L))
            if (input.isNdcVerified) add(AnalyticsIncrement(AnalyticsField.SAFETY_DRUG_CODE_VERIFIED, 1L))
            if (input.isSubstitute) add(AnalyticsIncrement(AnalyticsField.SAFETY_SUBSTITUTE_FLAGGED, 1L))

            if (input.isHazardous && input.isHazardousDrugEnabled) {
                if (input.isGlovesPresent) {
                    add(AnalyticsIncrement(AnalyticsField.COMPLIANCE_GLOVES_DETECTED, 1L))
                } else {
                    add(AnalyticsIncrement(AnalyticsField.COMPLIANCE_GLOVES_NOT_DETECTED, 1L))
                }
                if (input.hazardousTrayDetected == true) {
                    add(AnalyticsIncrement(AnalyticsField.COMPLIANCE_HAZARDOUS_TRAY_DETECTED, 1L))
                }
            }

            val target = input.targetCount
            val final = input.finalPillCount
            if (target != null && target > 0 && final != null && final != target) {
                add(AnalyticsIncrement(AnalyticsField.ACCURACY_COUNT_MISMATCH, 1L))
                add(AnalyticsIncrement(AnalyticsField.ACCURACY_VARIANCE_ABS_SUM, abs(final - target).toLong()))
            }

            if (input.recountAttempts > 0) {
                add(AnalyticsIncrement(AnalyticsField.ACCURACY_RECOUNT_ATTEMPTS_SUM, input.recountAttempts.toLong()))
            }
            if (input.manualEntries > 0) {
                add(AnalyticsIncrement(AnalyticsField.ACCURACY_MANUAL_ENTRY, input.manualEntries.toLong()))
            }
            if (input.backcountPerformed) add(AnalyticsIncrement(AnalyticsField.ACCURACY_BACKCOUNT_PERFORMED, 1L))
            if (input.backcountMismatch) add(AnalyticsIncrement(AnalyticsField.ACCURACY_BACKCOUNT_MISMATCH, 1L))
        }

    fun stockIncrements(input: StockAnalyticsInput): List<AnalyticsIncrement> =
        buildList {
            val isCycle = input.workType == AnalyticsWorkType.INVENTORY_CYCLE_COUNT

            if (input.countTransaction) {
                add(
                    AnalyticsIncrement(
                        if (isCycle) AnalyticsField.INVENTORY_CYCLE_COUNT
                        else AnalyticsField.INVENTORY_STOCK_COUNT,
                        1L,
                    )
                )
                // Inventory enters totalVolume, and pms_transactions is derived as
                // totalVolume - manual_transactions. A count the PMS did not request must raise
                // the manual counter, or subtraction silently reports it as PMS-initiated.
                // INVENTORY_STOCK_COUNT is exactly the "no requestIdFromPMS" case, which is why
                // the work type rather than a separate flag decides this.
                if (!isCycle) {
                    add(AnalyticsIncrement(AnalyticsField.ORIGIN_MANUAL_TRANSACTIONS, 1L))
                }
            }

            // Deltas, so a correction is negative and must still be applied.
            if (input.sealedBottleQty != 0L) {
                add(
                    AnalyticsIncrement(
                        if (isCycle) AnalyticsField.INVENTORY_CYCLE_SEALED_BOTTLES_SCANNED
                        else AnalyticsField.INVENTORY_STOCK_SEALED_BOTTLES_SCANNED,
                        input.sealedBottleQty,
                    )
                )
            }

            if (input.looseLineCount != 0L) {
                add(AnalyticsIncrement(AnalyticsField.ORIGIN_OPEN_PILL, input.looseLineCount))
            }
        }

    /**
     * The drug code to record in the work type's distinct-code set, if there is one.
     *
     * Emitted once per transaction, alongside the transaction count. A stock transaction is one
     * drug, so its later lines carry the same code — the set would de-duplicate them anyway, and
     * skipping them keeps a no-change event from writing to the row at all.
     */
    fun stockInsertions(input: StockAnalyticsInput): List<AnalyticsSetInsertion> {
        if (!input.countTransaction) return emptyList()
        val ndc = input.ndc?.trim().orEmpty()
        if (ndc.isEmpty()) return emptyList()
        return listOf(AnalyticsSetInsertion(input.workType, ndc))
    }

    /**
     * The final pill count for a dispense.
     *
     * Reads the [com.dispensesure.retail.core.models.DISPENSED_QUANTITY_STEP] rows, falling back to
     * untyped rows, and deliberately **ignores the recount step**. This is the same number the HL7
     * message sends to the pharmacy system, so `variance_abs_sum` describes the variance the PMS
     * actually recorded and the two systems reconcile during an audit. Preferring the recount would
     * report a different quantity than the PMS holds for the same dispense.
     *
     * The recount is not lost: `backcount_performed`, `backcount_mismatch` and
     * `recount_attempts_sum` all carry it on the wire.
     */
    fun finalPillCount(verification: Int?, untyped: Int?): Int? = verification ?: untyped

    /**
     * Derived duration from [createdAt] to [completedAt] in seconds.
     *
     * Returns null when:
     * - elapsed is negative (clock moved backward)
     * - elapsed exceeds the 4-hour ceiling (leaves duration out of sum, not min)
     */
    fun derivedDurationSeconds(createdAt: Long, completedAt: Long): Long? {
        val elapsedMs = completedAt - createdAt
        if (elapsedMs < 0L) return null
        val ceilingMs = 4L * 60 * 60 * 1000
        if (elapsedMs > ceilingMs) return null
        return (elapsedMs / 1000.0).roundToLong()
    }
}
