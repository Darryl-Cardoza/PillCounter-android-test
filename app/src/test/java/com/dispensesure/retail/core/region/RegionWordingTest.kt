package com.dispensesure.retail.core.region

import com.dispensesure.retail.R
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionWordingTest {

    @Test
    fun `US keeps every id`() {
        assertEquals(R.string.ndc_number, Region.US.wording(R.string.ndc_number))
        assertEquals(R.string.kpi_hazardous, Region.US.wording(R.string.kpi_hazardous))
    }

    @Test
    fun `canada swaps NDC and dashboard wording`() {
        assertEquals(R.string.ndc_number_ca, Region.CA.wording(R.string.ndc_number))
        assertEquals(R.string.kpi_hazardous_ca, Region.CA.wording(R.string.kpi_hazardous))
        assertEquals(R.string.kpi_cont_drugs_ca, Region.CA.wording(R.string.kpi_cont_drugs))
    }

    @Test
    fun `canada keeps strings that have no canada wording`() {
        assertEquals(R.string.expiry, Region.CA.wording(R.string.expiry))
    }
}
