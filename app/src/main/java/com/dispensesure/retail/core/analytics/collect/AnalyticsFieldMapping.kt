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
 * One completed stock transaction, ready to be turned into increments.
 *
 * Counted at transaction completion rather than per scan: a sealed lot uses fetch-or-create, so
 * N scans of one lot produce a single bottle line with `bottleQty = N`. Reading the final quantity
 * once reports bottles rather than lines, and also captures quantities edited after the first scan,
 * which a per-scan event never sees.
 *
 * @property sealedBottleQty Total sealed bottles across the transaction's lines; 0 when the count
 *   was loose pills only.
 * @property looseLineCount Lines holding loose pills — each is one opened container.
 * @property ndc The drug code, recorded into the work type's distinct-code set.
 */
data class StockAnalyticsInput(
    val workType: AnalyticsWorkType,
    val sealedBottleQty: Long,
    val looseLineCount: Long,
    val ndc: String?,
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

            add(
                AnalyticsIncrement(
                    if (isCycle) AnalyticsField.INVENTORY_CYCLE_COUNT
                    else AnalyticsField.INVENTORY_STOCK_COUNT,
                    1L,
                )
            )

            if (input.sealedBottleQty > 0) {
                add(
                    AnalyticsIncrement(
                        if (isCycle) AnalyticsField.INVENTORY_CYCLE_SEALED_BOTTLES_SCANNED
                        else AnalyticsField.INVENTORY_STOCK_SEALED_BOTTLES_SCANNED,
                        input.sealedBottleQty,
                    )
                )
            }

            if (input.looseLineCount > 0) {
                add(AnalyticsIncrement(AnalyticsField.ORIGIN_OPEN_PILL, input.looseLineCount))
            }
        }

    /** The drug code to record in the work type's distinct-code set, if there is one. */
    fun stockInsertions(input: StockAnalyticsInput): List<AnalyticsSetInsertion> {
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
