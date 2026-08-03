package com.rite.pillcounting.feature.dashboard.presentation.model

import androidx.navigation.NavController
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardUiState
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardVariantParamsTest {

    private val uiState: DashboardUiState = mockk(relaxed = true)
    private val navController: NavController = mockk(relaxed = true)

    private val onKpiFilterTapped: (KpiFilter) -> Unit = {}
    private val onTabSelected: (DashboardTab) -> Unit = {}
    private val onDispenseQuickAction: () -> Unit = {}
    private val onInventoryQuickAction: () -> Unit = {}
    private val onRecentDispenseClick: (Long) -> Unit = {}
    private val onRecentBatchClick: (Long) -> Unit = {}
    private val onQueueDispenseClick: (Long) -> Unit = {}
    private val onQueueInventoryClick: (Long) -> Unit = {}

    private fun sample() = DashboardVariantParams(
        uiState = uiState,
        isPmsConnected = true,
        isHl7Enabled = false,
        navController = navController,
        onKpiFilterTapped = onKpiFilterTapped,
        onTabSelected = onTabSelected,
        onDispenseQuickAction = onDispenseQuickAction,
        onInventoryQuickAction = onInventoryQuickAction,
        onRecentDispenseClick = onRecentDispenseClick,
        onRecentBatchClick = onRecentBatchClick,
        onQueueDispenseClick = onQueueDispenseClick,
        onQueueInventoryClick = onQueueInventoryClick,
        disabledKpiFilters = emptySet(),
        onDisabledKpiFilterTapped = {},
    )

    @Test
    fun getters_returnConstructorValues() {
        val p = sample()
        assertSame(uiState, p.uiState)
        assertTrue(p.isPmsConnected)
        assertEquals(false, p.isHl7Enabled)
        assertSame(navController, p.navController)
        assertSame(onKpiFilterTapped, p.onKpiFilterTapped)
        assertSame(onTabSelected, p.onTabSelected)
        assertSame(onDispenseQuickAction, p.onDispenseQuickAction)
        assertSame(onInventoryQuickAction, p.onInventoryQuickAction)
        assertSame(onRecentDispenseClick, p.onRecentDispenseClick)
        assertSame(onRecentBatchClick, p.onRecentBatchClick)
        assertSame(onQueueDispenseClick, p.onQueueDispenseClick)
        assertSame(onQueueInventoryClick, p.onQueueInventoryClick)
    }

    @Test
    fun equalsAndHashCode() {
        val a = sample()
        val b = sample()
        val c = a.copy(isPmsConnected = false)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun toString_containsClassName() {
        assertTrue(sample().toString().contains("DashboardVariantParams"))
    }

    @Test
    fun copy_overridesField() {
        val copy = sample().copy(isHl7Enabled = true, isPmsConnected = false)
        assertTrue(copy.isHl7Enabled)
        assertEquals(false, copy.isPmsConnected)
        assertSame(uiState, copy.uiState)
    }

    @Test
    fun componentFunctions() {
        val p = sample()
        assertSame(uiState, p.component1())
        assertTrue(p.component2())
        assertEquals(false, p.component3())
        assertSame(navController, p.component4())
        assertSame(onKpiFilterTapped, p.component5())
        assertSame(onTabSelected, p.component6())
        assertSame(onDispenseQuickAction, p.component7())
        assertSame(onInventoryQuickAction, p.component8())
        assertSame(onRecentDispenseClick, p.component9())
        assertSame(onRecentBatchClick, p.component10())
        assertSame(onQueueDispenseClick, p.component11())
        assertSame(onQueueInventoryClick, p.component12())
    }

    @Test
    fun lambdas_areInvokable() {
        // Construct with real (non-empty) lambdas and verify they execute.
        var kpi: KpiFilter? = null
        var tab: DashboardTab? = null
        var dispense = false
        var inventory = false
        var recentDispenseId = -1L
        var recentBatchId = -1L
        var queueDispenseId = -1L
        var queueInventoryId = -1L

        val p = DashboardVariantParams(
            uiState = uiState,
            isPmsConnected = false,
            isHl7Enabled = true,
            navController = navController,
            onKpiFilterTapped = { kpi = it },
            onTabSelected = { tab = it },
            onDispenseQuickAction = { dispense = true },
            onInventoryQuickAction = { inventory = true },
            onRecentDispenseClick = { recentDispenseId = it },
            onRecentBatchClick = { recentBatchId = it },
            onQueueDispenseClick = { queueDispenseId = it },
            onQueueInventoryClick = { queueInventoryId = it },
            disabledKpiFilters = emptySet(),
            onDisabledKpiFilterTapped = {},
        )

        p.onKpiFilterTapped(KpiFilter.DISP_PENDING)
        p.onTabSelected(DashboardTab.entries.first())
        p.onDispenseQuickAction()
        p.onInventoryQuickAction()
        p.onRecentDispenseClick(1L)
        p.onRecentBatchClick(2L)
        p.onQueueDispenseClick(3L)
        p.onQueueInventoryClick(4L)

        assertEquals(KpiFilter.DISP_PENDING, kpi)
        assertEquals(DashboardTab.entries.first(), tab)
        assertTrue(dispense)
        assertTrue(inventory)
        assertEquals(1L, recentDispenseId)
        assertEquals(2L, recentBatchId)
        assertEquals(3L, queueDispenseId)
        assertEquals(4L, queueInventoryId)
    }
}
