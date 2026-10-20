package com.dispensesure.retail.core.scanning.domain.model

/**
 * A decoded barcode. [gtin14] is null when the value is not a valid GTIN; [expiry] is MM-dd-yyyy.
 * [lookupCode] is what the drug is looked up by: the GTIN-14, or for Canada a plain code as scanned.
 */
data class DecodedScan(
    val isGs1: Boolean,
    val gtin14: String?,
    val lot: String?,
    val expiry: String?,
    val serial: String?,
    val lookupCode: String? = gtin14,
)
