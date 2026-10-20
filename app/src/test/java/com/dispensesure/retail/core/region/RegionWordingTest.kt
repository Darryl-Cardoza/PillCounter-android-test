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

    @Test
    fun `canada hazardous settings and tray warnings say cytotoxic`() {
        assertEquals(R.string.setting_hazardous_drug_ca, Region.CA.wording(R.string.setting_hazardous_drug))
        assertEquals(R.string.hazardous_tray_warning_ca, Region.CA.wording(R.string.hazardous_tray_warning))
        assertEquals(R.string.hazardous_tray_warning, Region.US.wording(R.string.hazardous_tray_warning))
    }

    @Test
    fun `canada HL7 notifications say DIN`() {
        assertEquals(R.string.hl7_notification_drug_not_found_no_drug_ca, Region.CA.wording(R.string.hl7_notification_drug_not_found_no_drug))
        assertEquals(R.string.hl7_notification_drug_not_found_no_drug, Region.US.wording(R.string.hl7_notification_drug_not_found_no_drug))
    }
}
