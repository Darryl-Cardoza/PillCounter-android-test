package com.dispensesure.retail.feature.dashboard.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class KpiFilterTest {

    @Test
    fun `dispense filters open the dispense queue`() {
        listOf(
            KpiFilter.DISP_HIGH_PRIORITY,
            KpiFilter.DISP_PENDING,
            KpiFilter.DISP_CONTROLLED,
            KpiFilter.DISP_HAZARDOUS,
        ).forEach { assertEquals(it.name, DashboardTab.DISPENSE_QUEUE, it.queue) }
    }

    @Test
    fun `inventory filters open the inventory queue`() {
        listOf(KpiFilter.INV_CYCLE_COUNT, KpiFilter.INV_PENDING_BATCH)
            .forEach { assertEquals(it.name, DashboardTab.INVENTORY_QUEUE, it.queue) }
    }
}
