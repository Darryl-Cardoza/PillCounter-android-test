package org.rite.hl7.builder.pharmacy.rxe

import org.rite.hl7.builder.pharmacy.rxe.RxeVersionCapabilities
import org.rite.hl7.domain.utils.HL7Utils
import org.rite.hl7.domain.utils.HL7Utils.buildComponent
import org.rite.hl7.domain.model.MedicationData



/**
 * Builds the HL7 RXE (Pharmacy/Treatment Encoded Order) segment.
 * Version-safe for HL7 v2.1 → v2.8
 */
fun buildRXE(
    medication: MedicationData,
    hl7Version: String = "2.5"
): String {

    /** RXE-2: Drug ordered (code ^ text ^ coding system) */
    val giveCode = buildComponent(
        medication.drugCode,
        medication.drugName,
        medication.drugCodeSystem ?: ""
    )

    /** RXE-5: Units of quantity ordered (code ^ text) */
    val giveUnits = buildComponent(
        medication.qtyUnitCode ?: "",
        medication.qtyUnitText ?: ""
    )

    /** RXE-6: Dosage form (code ^ text) */
    val dosageForm = buildComponent(
        medication.dosageFormCode ?: "",
        medication.dosageFormText ?: ""
    )

    /** RXE-11: Units used for dispensing (code ^ text) */
    val dispenseUnits = buildComponent(
        medication.dispenseUnitsCode ?: "",
        medication.dispenseUnitsText ?: ""
    )

    /** RXE-14: Pharmacist verifier */
    val pharmacist = buildComponent(
        medication.pharmacistVerifierId ?: ""
    )

    /**
     * Canonical RXE field list (RXE-1 → RXE-21)
     * Index = RXE field number - 1
     */
    val allFields = listOf(

        /* RXE-1: Quantity/Timing */
        "",

        /* RXE-2: Give Code */
        giveCode,

        /* RXE-3: Requested Give Amount */
        medication.requestedQty ?: "",

        /* RXE-4: Requested Give Amount Maximum */
        medication.requestedQtyMax ?: "",

        /* RXE-5: Give Units */
        giveUnits,

        /* RXE-6: Dosage Form */
        dosageForm,

        /* RXE-7: Administration Instructions */
        medication.adminInstructions ?: "",

        /* RXE-8: Deliver-To Location */
        medication.deliverToLocation ?: "",

        /* RXE-9: Substitution Status */
        "",

        /* RXE-10: Dispense Amount */
        medication.dispenseAmount ?: "",

        /* RXE-11: Dispense Units */
        dispenseUnits,

        /* RXE-12: Number of Refills */
        medication.numberOfRefills ?: "",

        /* RXE-13: Ordering Provider DEA Number */
        "",

        /* RXE-14: Pharmacist Verifier */
        pharmacist,

        /* RXE-15: Prescription Number */
        "",

        /* RXE-16: Number of Refills Remaining */
        "",

        /* RXE-17: Number of Doses Dispensed */
        "",

        /* RXE-18: Date/Time of Last Refill */
        "",

        /* RXE-19: Total Daily Dose */
        "",

        /* RXE-20: Needs Human Review Indicator */
        "",

        /* RXE-21: Pharmacy Instructions */
        medication.pharmacyInstructions ?: ""
    )

    /** Truncate fields safely based on HL7 version */
    val maxField = RxeVersionCapabilities.maxField(hl7Version)

    return HL7Utils.buildSegment(
        "RXE",
        *allFields.take(maxField).toTypedArray()
    )
}
