package com.dispensesure.retail.feature.dispense.domain.model

import com.dispensesure.retail.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DispenseCountEffectTest {

    @Test
    fun showToast_carriesTheText() {
        assertEquals(R.string.max_count_reached, DispenseCountEffect.ShowToast(R.string.max_count_reached).text)
    }

    @Test
    fun finished_isASingleton() {
        assertSame(DispenseCountEffect.Finished, DispenseCountEffect.Finished)
    }
}
