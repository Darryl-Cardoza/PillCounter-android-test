package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.model.AnalyticsWorkType
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsWorkTypeTest {

    @Test
    fun `hazardous controlled dispense classifies as controlled (hazardous is cross-cutting)`() {
        val result = AnalyticsWorkType.classify(
            isDispense = true,
            isHazardous = true,
            drugType = "C2",
            isPmsInitiated = false,
        )
        assertEquals(AnalyticsWorkType.DISPENSE_CONTROLLED, result)
    }

    @Test
    fun `hazardous normal dispense classifies as normal (hazardous is cross-cutting)`() {
        val result = AnalyticsWorkType.classify(
            isDispense = true,
            isHazardous = true,
            drugType = null,
            isPmsInitiated = false,
        )
        assertEquals(AnalyticsWorkType.DISPENSE_NORMAL, result)
    }

    @Test
    fun `non-blank drugType gives controlled for dispense`() {
        val result = AnalyticsWorkType.classify(
            isDispense = true,
            isHazardous = false,
            drugType = "C2",
            isPmsInitiated = false,
        )
        assertEquals(AnalyticsWorkType.DISPENSE_CONTROLLED, result)
    }

    @Test
    fun `blank drugType gives normal dispense`() {
        val result = AnalyticsWorkType.classify(
            isDispense = true,
            isHazardous = false,
            drugType = "",
            isPmsInitiated = false,
        )
        assertEquals(AnalyticsWorkType.DISPENSE_NORMAL, result)
    }

    @Test
    fun `null drugType gives normal dispense`() {
        val result = AnalyticsWorkType.classify(
            isDispense = true,
            isHazardous = false,
            drugType = null,
            isPmsInitiated = false,
        )
        assertEquals(AnalyticsWorkType.DISPENSE_NORMAL, result)
    }

    @Test
    fun `PMS flag picks cycle count for inventory`() {
        val result = AnalyticsWorkType.classify(
            isDispense = false,
            isHazardous = false,
            drugType = null,
            isPmsInitiated = true,
        )
        assertEquals(AnalyticsWorkType.INVENTORY_CYCLE_COUNT, result)
    }

    @Test
    fun `no PMS flag picks stock count for inventory`() {
        val result = AnalyticsWorkType.classify(
            isDispense = false,
            isHazardous = false,
            drugType = null,
            isPmsInitiated = false,
        )
        assertEquals(AnalyticsWorkType.INVENTORY_STOCK_COUNT, result)
    }
}
