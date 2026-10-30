package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.collect.AnalyticsFieldMapping
import com.dispensesure.retail.core.analytics.collect.DispenseAnalyticsInput
import com.dispensesure.retail.core.analytics.collect.StockAnalyticsInput
import com.dispensesure.retail.core.analytics.model.AnalyticsField
import com.dispensesure.retail.core.analytics.model.AnalyticsWorkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsFieldMappingTest {

    private fun baseDispense(
        workType: AnalyticsWorkType = AnalyticsWorkType.DISPENSE_NORMAL,
        isHazardous: Boolean = false,
        isComingFromHL7: Boolean = false,
        isNdcVerified: Boolean = false,
        isSubstitute: Boolean = false,
        isHazardousDrugEnabled: Boolean = false,
        isGlovesPresent: Boolean = false,
        hazardousTrayDetected: Boolean? = null,
        targetCount: Int? = null,
        finalPillCount: Int? = null,
        recountAttempts: Int = 0,
        manualEntries: Int = 0,
        backcountPerformed: Boolean = false,
        backcountMismatch: Boolean = false,
        createdAt: Long = 1000L,
        completedAt: Long = 2000L,
    ) = DispenseAnalyticsInput(
        workType = workType,
        isHazardous = isHazardous,
        isComingFromHL7 = isComingFromHL7,
        isNdcVerified = isNdcVerified,
        isSubstitute = isSubstitute,
        isHazardousDrugEnabled = isHazardousDrugEnabled,
        isGlovesPresent = isGlovesPresent,
        hazardousTrayDetected = hazardousTrayDetected,
        targetCount = targetCount,
        finalPillCount = finalPillCount,
        recountAttempts = recountAttempts,
        manualEntries = manualEntries,
        backcountPerformed = backcountPerformed,
        backcountMismatch = backcountMismatch,
        createdAt = createdAt,
        completedAt = completedAt,
    )

    @Test
    fun `dispense always increments work type count`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(baseDispense())
        assertTrue(result.any { it.field == AnalyticsField.DISPENSE_NORMAL_COUNT && it.delta == 1L })
    }

    @Test
    fun `non-HL7 increments originManualTransactions`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(baseDispense(isComingFromHL7 = false))
        assertTrue(result.any { it.field == AnalyticsField.ORIGIN_MANUAL_TRANSACTIONS })
    }

    @Test
    fun `HL7 does not increment originManualTransactions`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(baseDispense(isComingFromHL7 = true))
        assertTrue(result.none { it.field == AnalyticsField.ORIGIN_MANUAL_TRANSACTIONS })
    }

    @Test
    fun `isNdcVerified increments safetyDrugCodeVerified`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(baseDispense(isNdcVerified = true))
        assertTrue(result.any { it.field == AnalyticsField.SAFETY_DRUG_CODE_VERIFIED })
    }

    @Test
    fun `isSubstitute increments safetySubstituteFlagged`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(baseDispense(isSubstitute = true))
        assertTrue(result.any { it.field == AnalyticsField.SAFETY_SUBSTITUTE_FLAGGED })
    }

    @Test
    fun `hazardous setting off means no compliance counters`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(
            baseDispense(
                isHazardous = true,
                isHazardousDrugEnabled = false,
                isGlovesPresent = true,
                hazardousTrayDetected = true,
            )
        )
        assertTrue(result.none { it.field == AnalyticsField.COMPLIANCE_GLOVES_DETECTED })
        assertTrue(result.none { it.field == AnalyticsField.COMPLIANCE_GLOVES_NOT_DETECTED })
        assertTrue(result.none { it.field == AnalyticsField.COMPLIANCE_HAZARDOUS_TRAY_DETECTED })
    }

    @Test
    fun `gloves present when setting on increments glovesDetected`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(
            baseDispense(
                isHazardous = true,
                isHazardousDrugEnabled = true,
                isGlovesPresent = true,
            )
        )
        assertTrue(result.any { it.field == AnalyticsField.COMPLIANCE_GLOVES_DETECTED })
        assertTrue(result.none { it.field == AnalyticsField.COMPLIANCE_GLOVES_NOT_DETECTED })
    }

    @Test
    fun `hazardousTrayDetected null increments nothing`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(
            baseDispense(
                isHazardous = true,
                isHazardousDrugEnabled = true,
                hazardousTrayDetected = null,
            )
        )
        assertTrue(result.none { it.field == AnalyticsField.COMPLIANCE_HAZARDOUS_TRAY_DETECTED })
    }

    @Test
    fun `hazardous normal dispense increments both normal and hazardous count`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(
            baseDispense(workType = AnalyticsWorkType.DISPENSE_NORMAL, isHazardous = true)
        )
        assertTrue(result.any { it.field == AnalyticsField.DISPENSE_NORMAL_COUNT && it.delta == 1L })
        assertTrue(result.any { it.field == AnalyticsField.DISPENSE_HAZARDOUS_COUNT && it.delta == 1L })
    }

    @Test
    fun `hazardous controlled dispense increments both controlled and hazardous count`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(
            baseDispense(workType = AnalyticsWorkType.DISPENSE_CONTROLLED, isHazardous = true)
        )
        assertTrue(result.any { it.field == AnalyticsField.DISPENSE_CONTROLLED_COUNT && it.delta == 1L })
        assertTrue(result.any { it.field == AnalyticsField.DISPENSE_HAZARDOUS_COUNT && it.delta == 1L })
    }

    @Test
    fun `non-hazardous dispense does not increment hazardous count`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(baseDispense(isHazardous = false))
        assertTrue(result.none { it.field == AnalyticsField.DISPENSE_HAZARDOUS_COUNT })
    }

    @Test
    fun `count mismatch increments accuracyCountMismatch and varianceAbsSum`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(
            baseDispense(targetCount = 30, finalPillCount = 28)
        )
        assertTrue(result.any { it.field == AnalyticsField.ACCURACY_COUNT_MISMATCH })
        val variance = result.first { it.field == AnalyticsField.ACCURACY_VARIANCE_ABS_SUM }
        assertEquals(2L, variance.delta)
    }

    @Test
    fun `recount attempts sum is accumulated`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(baseDispense(recountAttempts = 3))
        val recount = result.first { it.field == AnalyticsField.ACCURACY_RECOUNT_ATTEMPTS_SUM }
        assertEquals(3L, recount.delta)
    }

    @Test
    fun `manual entries accumulated`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(baseDispense(manualEntries = 2))
        val manual = result.first { it.field == AnalyticsField.ACCURACY_MANUAL_ENTRY }
        assertEquals(2L, manual.delta)
    }

    @Test
    fun `backcountPerformed flag increments accuracyBackcountPerformed`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(baseDispense(backcountPerformed = true))
        assertTrue(result.any { it.field == AnalyticsField.ACCURACY_BACKCOUNT_PERFORMED })
    }

    @Test
    fun `backcountMismatch flag increments accuracyBackcountMismatch`() {
        val result = AnalyticsFieldMapping.dispenseIncrements(
            baseDispense(backcountPerformed = true, backcountMismatch = true)
        )
        assertTrue(result.any { it.field == AnalyticsField.ACCURACY_BACKCOUNT_MISMATCH })
    }

    // ── finalPillCount precedence ─────────────────────────────────────────────

    @Test
    fun `finalPillCount picks reverification over verification`() {
        assertEquals(10, AnalyticsFieldMapping.finalPillCount(reverification = 10, verification = 5, untyped = 2))
    }

    @Test
    fun `finalPillCount picks verification over untyped when no reverification`() {
        assertEquals(5, AnalyticsFieldMapping.finalPillCount(reverification = null, verification = 5, untyped = 2))
    }

    @Test
    fun `finalPillCount uses untyped when others null`() {
        assertEquals(2, AnalyticsFieldMapping.finalPillCount(reverification = null, verification = null, untyped = 2))
    }

    @Test
    fun `finalPillCount returns null when all null`() {
        assertNull(AnalyticsFieldMapping.finalPillCount(null, null, null))
    }

    // ── derivedDurationSeconds ────────────────────────────────────────────────

    @Test
    fun `derivedDurationSeconds rounds to nearest second`() {
        val seconds = AnalyticsFieldMapping.derivedDurationSeconds(createdAt = 0L, completedAt = 1500L)
        assertEquals(2L, seconds)
    }

    @Test
    fun `derivedDurationSeconds returns null for negative elapsed`() {
        assertNull(AnalyticsFieldMapping.derivedDurationSeconds(createdAt = 2000L, completedAt = 1000L))
    }

    @Test
    fun `derivedDurationSeconds returns null for elapsed over 4 hours`() {
        val fourHoursMs = 4L * 60 * 60 * 1000 + 1
        assertNull(AnalyticsFieldMapping.derivedDurationSeconds(createdAt = 0L, completedAt = fourHoursMs))
    }

    @Test
    fun `derivedDurationSeconds returns value for exactly 4 hours`() {
        val fourHoursMs = 4L * 60 * 60 * 1000
        assertEquals(14400L, AnalyticsFieldMapping.derivedDurationSeconds(createdAt = 0L, completedAt = fourHoursMs))
    }

    // ── Stock increments ──────────────────────────────────────────────────────

    @Test
    fun `stock increments include count, sealed bottle, and NDC count when hasNdc is true`() {
        val result = AnalyticsFieldMapping.stockIncrements(
            StockAnalyticsInput(
                workType = AnalyticsWorkType.INVENTORY_STOCK_COUNT,
                isFirstBottleOfTxn = true,
                isSealed = true,
                hasNdc = true,
            )
        )
        assertTrue(result.increments.any { it.field == AnalyticsField.INVENTORY_STOCK_COUNT })
        assertTrue(result.increments.any { it.field == AnalyticsField.INVENTORY_STOCK_SEALED_BOTTLES_SCANNED })
        assertTrue(result.increments.any { it.field == AnalyticsField.INVENTORY_STOCK_NDC_COUNT })
    }

    @Test
    fun `stock increments no NDC count when hasNdc is false`() {
        val result = AnalyticsFieldMapping.stockIncrements(
            StockAnalyticsInput(
                workType = AnalyticsWorkType.INVENTORY_STOCK_COUNT,
                isFirstBottleOfTxn = true,
                isSealed = true,
                hasNdc = false,
            )
        )
        assertTrue(result.increments.none { it.field == AnalyticsField.INVENTORY_STOCK_NDC_COUNT })
    }

    @Test
    fun `stock non-sealed increments openPill not sealedBottles`() {
        val result = AnalyticsFieldMapping.stockIncrements(
            StockAnalyticsInput(
                workType = AnalyticsWorkType.INVENTORY_STOCK_COUNT,
                isFirstBottleOfTxn = false,
                isSealed = false,
                hasNdc = true,
            )
        )
        assertTrue(result.increments.any { it.field == AnalyticsField.ORIGIN_OPEN_PILL })
        assertTrue(result.increments.none { it.field == AnalyticsField.INVENTORY_STOCK_SEALED_BOTTLES_SCANNED })
    }

    @Test
    fun `stock second bottle does not re-increment count`() {
        val result = AnalyticsFieldMapping.stockIncrements(
            StockAnalyticsInput(
                workType = AnalyticsWorkType.INVENTORY_STOCK_COUNT,
                isFirstBottleOfTxn = false,
                isSealed = true,
                hasNdc = true,
            )
        )
        assertTrue(result.increments.none { it.field == AnalyticsField.INVENTORY_STOCK_COUNT })
    }
}
