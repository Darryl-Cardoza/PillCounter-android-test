package com.rite.pillcounting.feature.dashboard.presentation.model

import com.rite.pillcounting.R
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KpiCardSpecTest {

    private fun sample() = KpiCardSpec(
        filter = KpiFilter.DISP_PENDING,
        lineOneRes = R.string.kpi_disp_short,
        lineTwoRes = R.string.kpi_pending,
        iconRes = R.drawable.partial,
    )

    @Test
    fun getters_returnConstructorValues() {
        val spec = sample()
        assertEquals(KpiFilter.DISP_PENDING, spec.filter)
        assertEquals(R.string.kpi_disp_short, spec.lineOneRes)
        assertEquals(R.string.kpi_pending, spec.lineTwoRes)
        assertEquals(R.drawable.partial, spec.iconRes)
    }

    @Test
    fun equalsAndHashCode() {
        val a = sample()
        val b = sample()
        val c = a.copy(filter = KpiFilter.DISP_HAZARDOUS)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun toString_containsClassName() {
        assertTrue(sample().toString().contains("KpiCardSpec"))
    }

    @Test
    fun copy_overridesField() {
        val copy = sample().copy(iconRes = R.drawable.warning)
        assertEquals(R.drawable.warning, copy.iconRes)
        assertEquals(KpiFilter.DISP_PENDING, copy.filter)
    }

    @Test
    fun componentFunctions() {
        val spec = sample()
        assertEquals(KpiFilter.DISP_PENDING, spec.component1())
        assertEquals(R.string.kpi_disp_short, spec.component2())
        assertEquals(R.string.kpi_pending, spec.component3())
        assertEquals(R.drawable.partial, spec.component4())
    }

    @Test
    fun defaultKpiCards_hasExpectedSizeAndContent() {
        assertEquals(6, DefaultKpiCards.size)

        val expected = listOf(
            KpiCardSpec(KpiFilter.DISP_HIGH_PRIORITY, R.string.kpi_disp_short, R.string.kpi_high_priority, R.drawable.priorityhigh),
            KpiCardSpec(KpiFilter.DISP_PENDING, R.string.kpi_disp_short, R.string.kpi_pending, R.drawable.partial),
            KpiCardSpec(KpiFilter.DISP_CONTROLLED, R.string.kpi_disp_short, R.string.kpi_cont_drugs, R.drawable.prescription_icon),
            KpiCardSpec(KpiFilter.DISP_HAZARDOUS, R.string.kpi_disp_short, R.string.kpi_hazardous, R.drawable.warning),
            KpiCardSpec(KpiFilter.INV_CYCLE_COUNT, R.string.kpi_inv_short, R.string.kpi_cycle_count, R.drawable.prescription_icon),
            KpiCardSpec(KpiFilter.INV_PENDING_BATCH, R.string.kpi_inv_short, R.string.kpi_pending_batch, R.drawable.stock),
        )

        assertEquals(expected, DefaultKpiCards)
    }

    @Test
    fun defaultKpiCards_filtersAreUniqueAndCoverAllExpected() {
        val filters = DefaultKpiCards.map { it.filter }
        assertEquals(filters.size, filters.toSet().size)
        assertTrue(filters.contains(KpiFilter.INV_CYCLE_COUNT))
        assertTrue(filters.contains(KpiFilter.INV_PENDING_BATCH))
    }
}
