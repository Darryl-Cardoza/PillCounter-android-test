package org.rite.hl7.domain.model


// ==================== DISPENSE (RXD) ====================
// Parsed representation of the HL7 RXD segment.
// Contains details of the medication actually dispensed to the patient.

data class DispenseData(

    /** Parsed dispense sub-identifier (RXD-1) **/
    val dispenseSubId: String? = null,

    /** Parsed dispensed drug code (RXD-2.1) **/
    val drugCode: String,

    /** Parsed dispensed drug name (RXD-2.2) **/
    val drugName: String,

    /** Parsed coding system for drug identifier (RXD-2.3) **/
    val drugCodeSystem: String? = null,

    /** Parsed date and time medication was dispensed (RXD-3) **/
    val dateTimeDispensed: String? = null,

    /** Parsed quantity actually dispensed (RXD-4) **/
    val quantityDispensed: String,

    /** Parsed unit code for dispensed quantity (RXD-5.1) **/
    val unitCode: String,

    /** Parsed unit text for dispensed quantity (RXD-5.2) **/
    val unitText: String? = null,

    /** Parsed dosage form code (tablet, vial, etc.) (RXD-6.1) **/
    val dosageFormCode: String? = null,

    /** Parsed dosage form text (RXD-6.2) **/
    val dosageFormText: String? = null,

    /** Parsed prescription number associated with dispense (RXD-7) **/
    val prescriptionNumber: String? = null,

    /** Parsed dispensing pharmacist identifier (RXD-10.1) **/
    val pharmacistId: String? = null,

    /** Parsed dispensing pharmacist family name (RXD-10.2) **/
    val pharmacistFamilyName: String? = null,

    /** Parsed dispensing pharmacist given name (RXD-10.3) **/
    val pharmacistGivenName: String? = null,

    /** Parsed substitution status or code (RXD-11) **/
    val substituteCode: String? = null,

    /** Parsed location where medication was delivered (RXD-13) **/
    val deliverToLocation: String? = null,

    /** Parsed human review requirement indicator (RXD-14) **/
    val needsHumanReview: String? = null,

    /** Parsed free-text dispensing notes (RXD-15) **/
    val dispensingNotes: String? = null,

    /** Parsed medication lot number (RXD-18) **/
    val lotNumber: String? = null,

    /** Parsed medication expiration date (RXD-19) **/
    val expirationDate: String? = null,

    /** Parsed manufacturer name of dispensed substance (RXD-20) **/
    val substanceManufacturerName: String? = null,

    /** Parsed automation-specific dispensing cell identifier **/
    val cellId: String? = null,

    /** Parsed automation-specific dispensing cell location **/
    val cellLocation: String? = null,

    /** Parsed dispense priority code from ZPR-1 (e.g. STAT, ROUTINE, URGENT) **/
    val dispensePriority: String? = null,

    /** Parsed dispense priority description from ZPR-2 **/
    val dispensePriorityText: String? = null
)
