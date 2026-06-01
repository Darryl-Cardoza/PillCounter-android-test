package org.rite.hl7.builder.pharmacy.rxd

import org.rite.hl7.builder.pharmacy.rxd.RxdVersionCapabilities
import org.rite.hl7.domain.model.DispenseData
import org.rite.hl7.domain.utils.HL7Utils
import org.rite.hl7.domain.utils.HL7Utils.buildComponent

/**
 * Serializes a DispenseData object into an HL7 RXD segment.
 *
 * RXD notes:
 * - RXD is stable across HL7 v2.1–v2.8
 * - Field positions must be preserved
 * - Empty placeholders are required
 */
fun buildRXD(
    dispense: DispenseData,
    hl7Version: String = "2.5"
): String {

    /** RXD-2: Dispense/Give Code (code ^ text ^ coding system) */
    val giveCode = buildComponent(
        dispense.drugCode,
        dispense.drugName,
        dispense.drugCodeSystem ?: ""
    )

    /** RXD-5: Actual Dispense Units (exclude route codes like PO/IV) */
    val dispenseUnits = buildComponent(
        dispense.unitCode.takeIf { it !in setOf("PO", "IV", "IM", "TOP") }
            ?: dispense.unitText
            ?: "",
        dispense.unitText ?: ""
    )

    /** RXD-6: Actual Dosage Form (code ^ text) */
    val dosageForm = buildComponent(
        dispense.dosageFormCode ?: "",
        dispense.dosageFormText ?: ""
    )

    /** RXD-10: Dispensing Provider (ID ^ family ^ given) */
    val dispensingProvider = buildComponent(
        dispense.pharmacistId ?: "",
        dispense.pharmacistFamilyName ?: "",
        dispense.pharmacistGivenName ?: ""
    )

    /**
     * Canonical RXD field list (RXD-1 → RXD-20).
     * Index = RXD field number - 1
     */
    val allFields = listOf(

        /** RXD-1: Dispense Sub-ID */
        dispense.dispenseSubId ?: "1",

        /** RXD-2: Dispense/Give Code */
        giveCode,

        /** RXD-3: Date/Time Dispensed */
        dispense.dateTimeDispensed ?: "",

        /** RXD-4: Quantity Dispensed */
        dispense.quantityDispensed,

        /** RXD-5: Actual Dispense Units */
        dispenseUnits,

        /** RXD-6: Actual Dosage Form */
        dosageForm,

        /** RXD-7: Prescription Number */
        dispense.prescriptionNumber ?: "",

        /** RXD-8: Number of Refills Remaining */
        "",

        /** RXD-9: Dispense Notes */
        "",

        /** RXD-10: Dispensing Provider */
        dispensingProvider,

        /** RXD-11: Substitution Code */
        dispense.substituteCode ?: "",

        /** RXD-12: Total Daily Dose */
        "",

        /** RXD-13: Deliver-To Location */
        dispense.deliverToLocation ?: "",

        /** RXD-14: Needs Human Review Indicator */
        dispense.needsHumanReview ?: "",

        /** RXD-15: Dispensing Notes */
        dispense.dispensingNotes ?: "",

        /** RXD-16: Prescription Number (duplicate/legacy) */
        "",

        /** RXD-17: Number of Doses Dispensed */
        "",

        /** RXD-18: Lot Number */
        dispense.lotNumber ?: "",

        /** RXD-19: Expiration Date */
        dispense.expirationDate ?: "",

        /** RXD-20: Substance Manufacturer Name */
        dispense.substanceManufacturerName ?: ""
    )

    /** Truncate safely for HL7 version */
    val maxField = RxdVersionCapabilities.maxField(hl7Version)

    return HL7Utils.buildSegment(
        "RXD",
        *allFields.take(maxField).toTypedArray()
    )
}
