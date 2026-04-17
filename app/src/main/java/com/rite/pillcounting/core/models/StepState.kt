package com.rite.pillcounting.core.models

import com.rite.pillcounting.R

enum class StepState {
    STOCK_COUNT,
    RX_LABEL,
    SCAN,
    CONTAINER_INITIATE,
    TARGET_VERIFICATION,
    TARGET_REVERIFICATION,
    VIAL,
    CONTAINER_PENDING
}

fun StepState.icon(): Int {
    return when (this) {
        StepState.SCAN -> R.drawable.ndc_scan
        StepState.CONTAINER_INITIATE -> R.drawable.count_pills_container
        StepState.TARGET_VERIFICATION -> R.drawable.pill_count
        StepState.TARGET_REVERIFICATION -> R.drawable.pills_recount
        StepState.VIAL -> R.drawable.vial_capture
        StepState.CONTAINER_PENDING -> R.drawable.count_pills_container
        StepState.RX_LABEL -> -1
        StepState.STOCK_COUNT -> -1
    }
}

fun StepState.titleRes(): Int {
    return when (this) {
        StepState.SCAN -> R.string.scan_container_qr_code
        StepState.CONTAINER_INITIATE -> R.string.count_pills_from_the_container
        StepState.TARGET_VERIFICATION -> R.string.count_prescribed_pills_quantity
        StepState.TARGET_REVERIFICATION -> R.string.recount_prescribed_quantity
        StepState.VIAL -> R.string.capture_photo_of_counted_pills_vial
        StepState.CONTAINER_PENDING -> R.string.count_pills_from_the_container
        StepState.RX_LABEL -> R.string.scan_rx_label
        StepState.STOCK_COUNT -> R.string.scan_stock_bottle

    }
}