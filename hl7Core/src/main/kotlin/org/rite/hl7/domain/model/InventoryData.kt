package org.rite.hl7.domain.model

// ==================== INVENTORY (EQU + INV) ====================
// Parsed representation of inventory equipment and its associated bins.
// Combines EQU (equipment) with related INV (inventory bin) segments.

data class InventoryData(

    /** Parsed equipment identifier code (EQU-1.1) **/
    val equipmentId: String,

    /** Parsed namespace for equipment identifier (EQU-1.2) **/
    val equipmentIdNamespace: String? = null,

    /** Parsed date and time of the inventory event (EQU-2) **/
    val eventDateTime: String? = null,

    /** Parsed current operational state of the equipment (EQU-3) **/
    val equipmentState: String? = null,

    /** Parsed human-readable equipment name **/
    val equipmentName: String? = null,

    /** Parsed equipment type or classification **/
    val equipmentType: String? = null,

    /** Parsed inventory bins associated with this equipment **/
    val bins: List<InventoryBinData> = emptyList()
)





// ==================== INVENTORY BIN (INV) ====================
// Parsed representation of the HL7 INV segment.
// Contains inventory stock and bin-level information.

data class InventoryBinData(

    /** Parsed substance identifier code (INV-1.1) **/
    val substanceId: String,

    /** Parsed substance name or description (INV-1.2) **/
    val substanceName: String? = null,

    /** Parsed coding system for substance identifier (INV-1.3) **/
    val substanceCodeSystem: String? = null,

    /** Parsed current substance status (available, expired, etc.) (INV-2) **/
    val substanceStatus: String? = null,

    /** Parsed container or bin identifier (INV-5) **/
    val cellId: String? =  null,

    /** Parsed physical location of the inventory bin **/
    val cellLocation: String? = null,

    /** Parsed current quantity on hand in the bin (INV-8) **/
    val quantityOnHand: String? = null,

    /** Parsed available quantity for dispensing (INV-9) **/
    val availableQuantity: String? = null,

    /** Parsed unit code for inventory quantity (INV-11.1) **/
    val quantityUnitCode: String? = null,

    /** Parsed unit text for inventory quantity (INV-11.2) **/
    val quantityUnitText: String? = null,

    /** Parsed expiration date of the substance (INV-12) **/
    val expirationDate: String? = null,

    /** Parsed lot or batch number of the substance (INV-16) **/
    val lotNumber: String? = null,

    /** Parsed manufacturer name of the substance (INV-17) **/
    val manufacturerName: String? = null,

    /** Parsed supplier or vendor name of the substance (INV-18) **/
    val supplierName: String? = null,

    /** Parsed quantity currently on order for this substance (INV-19) **/
    val onOrderQuantity: String? = null
)
