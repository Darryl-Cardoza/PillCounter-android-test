package com.dispensesure.retail.core.utils.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthScreenLookTest {

    @Test
    fun showLogoAndFooter_phoneLandscape_flagOff_hidden() {
        assertFalse(shouldShowLogoAndFooter(isLandscape = true, isTablet = false, showOnPhoneLandscape = false))
    }

    @Test
    fun showLogoAndFooter_phoneLandscape_flagOn_shown() {
        assertTrue(shouldShowLogoAndFooter(isLandscape = true, isTablet = false, showOnPhoneLandscape = true))
    }

    @Test
    fun showLogoAndFooter_phonePortrait_shown() {
        assertTrue(shouldShowLogoAndFooter(isLandscape = false, isTablet = false, showOnPhoneLandscape = false))
    }

    @Test
    fun showLogoAndFooter_tabletLandscape_shown() {
        assertTrue(shouldShowLogoAndFooter(isLandscape = true, isTablet = true, showOnPhoneLandscape = false))
    }

    @Test
    fun showLogoAndFooter_tabletPortrait_shown() {
        assertTrue(shouldShowLogoAndFooter(isLandscape = false, isTablet = true, showOnPhoneLandscape = false))
    }

    @Test
    fun showLogoAndFooter_defaultFlag_hidesOnPhoneLandscape() {
        assertFalse(shouldShowLogoAndFooter(isLandscape = true, isTablet = false))
    }

    @Test
    fun cardMaxWidth_phoneAndTablet_useTheirOwnValues() {
        assertEquals(CARD_MAX_WIDTH_PHONE, cardMaxWidth(isTablet = false))
        assertEquals(CARD_MAX_WIDTH_TABLET, cardMaxWidth(isTablet = true))
    }
}
