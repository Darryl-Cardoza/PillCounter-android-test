package com.dispensesure.retail.feature.dashboard.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardTourOfferTest {
    @Test
    fun `tour offer stays hidden while the session is locked`() {
        assertFalse(shouldShowTourOffer(pending = true, locked = true))
    }

    @Test
    fun `tour offer shows when pending and unlocked`() {
        assertTrue(shouldShowTourOffer(pending = true, locked = false))
    }
}
