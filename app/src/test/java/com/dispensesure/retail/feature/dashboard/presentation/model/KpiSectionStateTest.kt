package com.dispensesure.retail.feature.dashboard.presentation.model

import com.dispensesure.retail.R
import com.dispensesure.retail.feature.dashboard.domain.model.KpiFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KpiSectionStateTest {

    private val tappedFilters = mutableListOf<KpiFilter>()
    private val disabledTappedFilters = mutableListOf<KpiFilter>()

    private fun state(
        counts: Map<KpiFilter, Int> = emptyMap(),
        selectedFilter: KpiFilter? = null,
        disabledFilters: Set<KpiFilter> = emptySet(),
    ) = KpiSectionState(
        counts = counts,
        selectedFilter = selectedFilter,
        disabledFilters = disabledFilters,
        onKpiTapped = { tappedFilters += it },
        onDisabledKpiTapped = { disabledTappedFilters += it },
    )

    private fun List<KpiCardItem>.cardFor(filter: KpiFilter) = single { it.filter == filter }

    @Test
    fun toCardItems_followsDefaultKpiCardsOrder() {
        assertEquals(DefaultKpiCards.map { it.filter }, state().toCardItems().map { it.filter })
    }

    @Test
    fun toCardItems_titleIsFilterNameAndSubtitleIsQueue() {
        val card = state().toCardItems().cardFor(KpiFilter.DISP_HIGH_PRIORITY)
        assertEquals(R.string.kpi_high_priority, card.titleRes)
        assertEquals(R.string.kpi_disp_short, card.subtitleRes)
        assertEquals(R.drawable.priorityhigh, card.iconRes)
    }

    @Test
    fun toCardItems_usesCountOrZeroWhenMissing() {
        val cards = state(counts = mapOf(KpiFilter.DISP_HAZARDOUS to 7)).toCardItems()
        assertEquals(7, cards.cardFor(KpiFilter.DISP_HAZARDOUS).count)
        assertEquals(0, cards.cardFor(KpiFilter.DISP_CONTROLLED).count)
    }

    @Test
    fun toCardItems_onlySelectedFilterIsSelected() {
        val cards = state(selectedFilter = KpiFilter.INV_CYCLE_COUNT).toCardItems()
        assertTrue(cards.cardFor(KpiFilter.INV_CYCLE_COUNT).isSelected)
        assertEquals(1, cards.count { it.isSelected })
    }

    @Test
    fun toCardItems_nothingSelectedWhenNoFilter() {
        assertFalse(state().toCardItems().any { it.isSelected })
    }

    @Test
    fun enabledCardClick_callsOnKpiTappedOnly() {
        state().toCardItems().cardFor(KpiFilter.DISP_HAZARDOUS).onClick()
        assertEquals(listOf(KpiFilter.DISP_HAZARDOUS), tappedFilters)
        assertTrue(disabledTappedFilters.isEmpty())
    }

    @Test
    fun disabledCardClick_callsOnDisabledKpiTappedOnly() {
        val cards = state(disabledFilters = setOf(KpiFilter.DISP_HIGH_PRIORITY)).toCardItems()
        val card = cards.cardFor(KpiFilter.DISP_HIGH_PRIORITY)
        assertTrue(card.isDisabled)
        card.onClick()
        assertEquals(listOf(KpiFilter.DISP_HIGH_PRIORITY), disabledTappedFilters)
        assertTrue(tappedFilters.isEmpty())
    }
}
