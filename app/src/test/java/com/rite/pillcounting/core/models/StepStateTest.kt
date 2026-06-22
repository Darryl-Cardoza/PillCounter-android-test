package com.rite.pillcounting.core.models

import com.rite.pillcounting.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StepStateTest {

    @Test
    fun values_containsAllConstantsInOrder() {
        val expected = arrayOf(
            StepState.STOCK_COUNT,
            StepState.RX_LABEL,
            StepState.SCAN,
            StepState.CONTAINER_INITIATE,
            StepState.TARGET_VERIFICATION,
            StepState.TARGET_REVERIFICATION,
            StepState.VIAL,
            StepState.CONTAINER_PENDING
        )
        assertArrayEquals(expected, StepState.values())
        assertEquals(8, StepState.values().size)
    }

    @Test
    fun valueOf_eachConstant() {
        assertEquals(StepState.STOCK_COUNT, StepState.valueOf("STOCK_COUNT"))
        assertEquals(StepState.RX_LABEL, StepState.valueOf("RX_LABEL"))
        assertEquals(StepState.SCAN, StepState.valueOf("SCAN"))
        assertEquals(StepState.CONTAINER_INITIATE, StepState.valueOf("CONTAINER_INITIATE"))
        assertEquals(StepState.TARGET_VERIFICATION, StepState.valueOf("TARGET_VERIFICATION"))
        assertEquals(StepState.TARGET_REVERIFICATION, StepState.valueOf("TARGET_REVERIFICATION"))
        assertEquals(StepState.VIAL, StepState.valueOf("VIAL"))
        assertEquals(StepState.CONTAINER_PENDING, StepState.valueOf("CONTAINER_PENDING"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun valueOf_invalid_throws() {
        StepState.valueOf("UNKNOWN")
    }

    @Test
    fun icon_coversEveryBranch() {
        assertEquals(R.drawable.ndc_scan, StepState.SCAN.icon())
        assertEquals(R.drawable.count_pills_container, StepState.CONTAINER_INITIATE.icon())
        assertEquals(R.drawable.pill_count, StepState.TARGET_VERIFICATION.icon())
        assertEquals(R.drawable.pills_recount, StepState.TARGET_REVERIFICATION.icon())
        assertEquals(R.drawable.vial_capture, StepState.VIAL.icon())
        assertEquals(R.drawable.count_pills_container, StepState.CONTAINER_PENDING.icon())
        assertEquals(-1, StepState.RX_LABEL.icon())
        assertEquals(-1, StepState.STOCK_COUNT.icon())
    }

    @Test
    fun titleRes_coversEveryBranch() {
        assertEquals(R.string.scan_container_qr_code, StepState.SCAN.titleRes())
        assertEquals(R.string.count_pills_from_the_container, StepState.CONTAINER_INITIATE.titleRes())
        assertEquals(R.string.count_prescribed_pills_quantity, StepState.TARGET_VERIFICATION.titleRes())
        assertEquals(R.string.recount_prescribed_quantity, StepState.TARGET_REVERIFICATION.titleRes())
        assertEquals(R.string.capture_photo_of_counted_pills_vial, StepState.VIAL.titleRes())
        assertEquals(R.string.count_pills_from_the_container, StepState.CONTAINER_PENDING.titleRes())
        assertEquals(R.string.scan_rx_label, StepState.RX_LABEL.titleRes())
        assertEquals(R.string.scan_stock_bottle, StepState.STOCK_COUNT.titleRes())
    }
}
