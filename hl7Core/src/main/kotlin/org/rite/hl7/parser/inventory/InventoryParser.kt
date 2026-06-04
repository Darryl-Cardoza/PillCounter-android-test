package org.rite.hl7.parser.inventory

import org.rite.hl7.domain.model.EquipmentData
import org.rite.hl7.domain.model.InventoryItemData


/**
 * Parses EQU (Equipment Detail) segment
 * Used in INR^U06 and INU^U05 messages
 */
fun parseEquipment(
    segments: Map<String, List<List<String>>>,
    compSep: String
): EquipmentData? {

    val equ = segments["EQU"]?.firstOrNull() ?: return null

    // Parse EQU-2: Equipment identifier (ID^Text^CodingSystem)
    val equipmentIdParts = equ.getOrNull(2)?.split(compSep) ?: emptyList()

    // Parse EQU-3: Equipment location (ID^Text^CodingSystem)
    val locationParts = equ.getOrNull(3)?.split(compSep) ?: emptyList()

    // Parse EQU-4: Equipment type (ID^Text^CodingSystem)
    val typeParts = equ.getOrNull(4)?.split(compSep) ?: emptyList()

    return EquipmentData(
        instanceIdentifier = equ.getOrNull(1)?.takeIf { it.isNotBlank() },

        equipmentId = equipmentIdParts.getOrNull(0)?.takeIf { it.isNotBlank() },
        equipmentName = equipmentIdParts.getOrNull(1)?.takeIf { it.isNotBlank() },
        equipmentCodingSystem = equipmentIdParts.getOrNull(2)?.takeIf { it.isNotBlank() },

        locationId = locationParts.getOrNull(0)?.takeIf { it.isNotBlank() },
        locationName = locationParts.getOrNull(1)?.takeIf { it.isNotBlank() },
        locationCodingSystem = locationParts.getOrNull(2)?.takeIf { it.isNotBlank() },

        equipmentTypeId = typeParts.getOrNull(0)?.takeIf { it.isNotBlank() },
        equipmentTypeName = typeParts.getOrNull(1)?.takeIf { it.isNotBlank() },
        equipmentTypeCodingSystem = typeParts.getOrNull(2)?.takeIf { it.isNotBlank() },

        equipmentState = equ.getOrNull(5)?.takeIf { it.isNotBlank() },
        lastUpdateDateTime = equ.getOrNull(6)?.takeIf { it.isNotBlank() },
        eventDateTime = equ.getOrNull(7)?.takeIf { it.isNotBlank() },
        alertLevel = equ.getOrNull(8)?.takeIf { it.isNotBlank() },
        equipmentStateReason = equ.getOrNull(9)?.takeIf { it.isNotBlank() },
        localRemoteControlState = equ.getOrNull(10)?.takeIf { it.isNotBlank() },
        alertLevelTimestamp = equ.getOrNull(11)?.takeIf { it.isNotBlank() }
    )
}

/**
 * Parses INV (Inventory Detail) segments
 * Supports both INR^U06 (request) and INU^U05 (update) messages
 * Returns list of inventory items (one per cell/bin)
 */
fun parseInventoryItems(
    segments: Map<String, List<List<String>>>,
    compSep: String
): List<InventoryItemData> {

    val invSegments = segments["INV"] ?: return emptyList()

    return invSegments.map { inv ->

        // Parse INV-1: Substance identifier (Code^Description^CodingSystem)
        val substanceParts = inv.getOrNull(1)?.split(compSep) ?: emptyList()

        // Parse INV-2: Substance status (Code^Description^CodingSystem)
        val statusParts = inv.getOrNull(2)?.split(compSep) ?: emptyList()

        // Parse INV-3: Substance type (Code^Description^CodingSystem)
        val typeParts = inv.getOrNull(3)?.split(compSep) ?: emptyList()

        // Parse INV-4: Container identifier (CellID^CellName^CodingSystem)
        val containerParts = inv.getOrNull(4)?.split(compSep) ?: emptyList()

        // Parse INV-11: Quantity units (Code^Description^CodingSystem)
        val unitParts = inv.getOrNull(11)?.split(compSep) ?: emptyList()

        InventoryItemData(
            substanceCode = substanceParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            substanceDescription = substanceParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            substanceCodingSystem = substanceParts.getOrNull(2)?.takeIf { it.isNotBlank() },

            substanceStatusCode = statusParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            substanceStatusDescription = statusParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            substanceStatusCodingSystem = statusParts.getOrNull(2)?.takeIf { it.isNotBlank() },

            substanceTypeCode = typeParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            substanceTypeDescription = typeParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            substanceTypeCodingSystem = typeParts.getOrNull(2)?.takeIf { it.isNotBlank() },

            containerId = containerParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            containerName = containerParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            containerCodingSystem = containerParts.getOrNull(2)?.takeIf { it.isNotBlank() },

            containerCarrierId = inv.getOrNull(5)?.takeIf { it.isNotBlank() },
            positionWithinCarrier = inv.getOrNull(6)?.takeIf { it.isNotBlank() },

            // Quantity fields (INV-7 through INV-10)
            initialQuantity = inv.getOrNull(7)?.takeIf { it.isNotBlank() },
            currentQuantity = inv.getOrNull(8)?.takeIf { it.isNotBlank() },
            availableQuantity = inv.getOrNull(9)?.takeIf { it.isNotBlank() },
            consumptionQuantity = inv.getOrNull(10)?.takeIf { it.isNotBlank() },

            quantityUnitCode = unitParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            quantityUnitDescription = unitParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            quantityUnitCodingSystem = unitParts.getOrNull(2)?.takeIf { it.isNotBlank() },

            expirationDateTime = inv.getOrNull(12)?.takeIf { it.isNotBlank() },
            firstUsedDateTime = inv.getOrNull(13)?.takeIf { it.isNotBlank() },
            onBoardStabilityDuration = inv.getOrNull(14)?.takeIf { it.isNotBlank() },
            testFluidIdentifier = inv.getOrNull(15)?.takeIf { it.isNotBlank() },
            lotNumber = inv.getOrNull(16)?.takeIf { it.isNotBlank() },
            manufacturerId = inv.getOrNull(17)?.takeIf { it.isNotBlank() },
            supplierId = inv.getOrNull(18)?.takeIf { it.isNotBlank() },
            onBoardStabilityTime = inv.getOrNull(19)?.takeIf { it.isNotBlank() },
            targetValue = inv.getOrNull(20)?.takeIf { it.isNotBlank() }
        )
    }
}