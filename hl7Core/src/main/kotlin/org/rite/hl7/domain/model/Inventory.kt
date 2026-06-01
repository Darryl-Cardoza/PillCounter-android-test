package org.rite.hl7.domain.model


/**
 * EQU - Equipment Detail Segment
 * Used in INR^U06 and INU^U05 messages to identify robot/equipment
 */
data class EquipmentData(
    /** EQU-1: Equipment instance identifier **/
    val instanceIdentifier: String? = null,

    /** EQU-2: Equipment identifier (ID^Text^CodingSystem) **/
    val equipmentId: String? = null,
    val equipmentName: String? = null,
    val equipmentCodingSystem: String? = null,

    /** EQU-3: Equipment location (ID^Text^CodingSystem) **/
    val locationId: String? = null,
    val locationName: String? = null,
    val locationCodingSystem: String? = null,

    /** EQU-4: Equipment type (ID^Text^CodingSystem) **/
    val equipmentTypeId: String? = null,
    val equipmentTypeName: String? = null,
    val equipmentTypeCodingSystem: String? = null,

    /** EQU-5: Equipment state (A=Active, I=Inactive, etc.) **/
    val equipmentState: String? = null,

    /** EQU-6: Last status/update datetime (YYYYMMDDHHMMSS) **/
    val lastUpdateDateTime: String? = null,

    /** EQU-7: Event date/time **/
    val eventDateTime: String? = null,

    /** EQU-8: Alert level **/
    val alertLevel: String? = null,

    /** EQU-9: Equipment state reason **/
    val equipmentStateReason: String? = null,

    /** EQU-10: Local/remote control state **/
    val localRemoteControlState: String? = null,

    /** EQU-11: Alert level timestamp **/
    val alertLevelTimestamp: String? = null
)

/**
 * INV - Inventory Detail Segment
 * Used in both INR^U06 (request) and INU^U05 (update) messages
 */
data class InventoryItemData(
    /** INV-1: Substance identifier (Code^Description^CodingSystem) **/
    val substanceCode: String? = null,
    val substanceDescription: String? = null,
    val substanceCodingSystem: String? = null,

    /** INV-2: Substance status (Code^Description^CodingSystem) **/
    val substanceStatusCode: String? = null,
    val substanceStatusDescription: String? = null,
    val substanceStatusCodingSystem: String? = null,

    /** INV-3: Substance type (Code^Description^CodingSystem) **/
    val substanceTypeCode: String? = null,
    val substanceTypeDescription: String? = null,
    val substanceTypeCodingSystem: String? = null,

    /** INV-4: Inventory container identifier (CellID^CellName^CodingSystem) **/
    val containerId: String? = null,
    val containerName: String? = null,
    val containerCodingSystem: String? = null,

    /** INV-5: Container carrier identifier **/
    val containerCarrierId: String? = null,

    /** INV-6: Position within carrier **/
    val positionWithinCarrier: String? = null,

    /** INV-7: Initial quantity (quantity before adjustment/count) **/
    val initialQuantity: String? = null,

    /** INV-8: Current quantity (physical count after cycle count) **/
    val currentQuantity: String? = null,

    /** INV-9: Available quantity (available for use) **/
    val availableQuantity: String? = null,

    /** INV-10: Consumption quantity / Usage per dispense **/
    val consumptionQuantity: String? = null,

    /** INV-11: Quantity units (Code^Description^CodingSystem) **/
    val quantityUnitCode: String? = null,
    val quantityUnitDescription: String? = null,
    val quantityUnitCodingSystem: String? = null,

    /** INV-12: Expiration date/time (YYYYMMDD or YYYYMMDDHHMMSS) **/
    val expirationDateTime: String? = null,

    /** INV-13: First used date/time **/
    val firstUsedDateTime: String? = null,

    /** INV-14: On board stability duration **/
    val onBoardStabilityDuration: String? = null,

    /** INV-15: Test/fluid identifier **/
    val testFluidIdentifier: String? = null,

    /** INV-16: Manufacturer lot number **/
    val lotNumber: String? = null,

    /** INV-17: Manufacturer identifier **/
    val manufacturerId: String? = null,

    /** INV-18: Supplier identifier **/
    val supplierId: String? = null,

    /** INV-19: On board stability time **/
    val onBoardStabilityTime: String? = null,

    /** INV-20: Target value **/
    val targetValue: String? = null
)

/**
 * Legacy InventoryData - kept for backward compatibility
 */
//data class InventoryData(
//    val inventoryId: String? = null,
//    val itemDescription: String? = null,
//    val quantity: String? = null,
//    val location: String? = null,
//    val status: String? = null
//)
