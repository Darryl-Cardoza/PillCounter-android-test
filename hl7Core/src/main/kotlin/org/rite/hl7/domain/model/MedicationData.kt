package org.rite.hl7.domain.model

// ==================== MEDICATION (RXE) ====================
// Parsed representation of the HL7 RXE segment.
// Contains medication order details required for dispensing and administration.

data class MedicationData(

    /** Parsed medication identifier code (RXE-2.1) **/
    val drugCode: String,

    /** Parsed medication display name (RXE-2.2) **/
    val drugName: String,

    /** Parsed coding system for medication identifier (e.g., RXNORM) (RXE-2.3) **/
    val drugCodeSystem: String? = null,

    /** Parsed quantity requested to be dispensed (RXE-3) **/
    val requestedQty: String? = null,

    /** Parsed maximum quantity allowed to dispense (RXE-4) **/
    val requestedQtyMax: String? = null,

    /** Parsed unit code for requested quantity (RXE-5.1) **/
    val qtyUnitCode: String? = null,

    /** Parsed unit text for requested quantity (RXE-5.2) **/
    val qtyUnitText: String? = null,

    /** Parsed dosage form code indicating medication form (RXE-6.1) **/
    val dosageFormCode: String? = null,

    /** Parsed dosage form text (RXE-6.2) **/
    val dosageFormText: String? = null,

    /** Parsed administration instructions for the medication (RXE-7) **/
    val adminInstructions: String? = null,

    /** Parsed location where medication should be delivered (RXE-8) **/
    val deliverToLocation: String? = null,

    /** Parsed amount to dispense per action (RXE-10) **/
    val dispenseAmount: String? = null,

    /** Parsed unit code for dispensed amount (RXE-11.1) **/
    val dispenseUnitsCode: String? = null,

    /** Parsed unit text for dispensed amount (RXE-11.2) **/
    val dispenseUnitsText: String? = null,

    /** Parsed number of refills allowed (RXE-12) **/
    val numberOfRefills: String? = null,

    /** Parsed pharmacist identifier who verified the order (RXE-14.1) **/
    val pharmacistVerifierId: String? = null,

    /** Parsed free-text pharmacy instructions (RXE-21) **/
    val pharmacyInstructions: String? = null
)
