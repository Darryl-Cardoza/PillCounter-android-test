package com.rite.pillcounting.feature.dispenseFlow.domain.model

import java.time.LocalDate

/**
 * Represents structured and type-safe GS1 barcode data.
 */
data class BarcodeData(
    val gtin: String? = null,
    val lotNumber: String? = null,
    val serialNumber: String? = null,
    val productionDate: LocalDate? = null,
    val packingDate: LocalDate? = null,
    val sellByDate: LocalDate? = null,
    val expirationDate: LocalDate? = null,
    val netWeightKg: Double? = null,
    val grossWeightKg: Double? = null,
    val netWeightLb: Double? = null,
    val grossWeightLb: Double? = null
)