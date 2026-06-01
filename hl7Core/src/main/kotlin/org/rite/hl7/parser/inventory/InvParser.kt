package org.rite.hl7.parser.inventory

import org.rite.hl7.domain.model.InventoryBinData


/**
 * Parses an INV segment and extracts inventory bin information.
 * Each INV represents a single storage bin or container.
 */
fun parseInventoryBin(
    inv: List<String>,
    compSep: String
): InventoryBinData {

    /** Parsed substance identifier components from INV-1 **/
    val substanceParts = inv.getOrElse(1) { "" }.split(compSep)

    /** Parsed quantity unit components from INV-11 **/
    val unitParts = inv.getOrNull(11)?.split(compSep) ?: emptyList()

    /** Build and return parsed inventory bin data **/
    return InventoryBinData(
        substanceId = substanceParts.getOrNull(0) ?: "",
        substanceName = substanceParts.getOrNull(1)?.takeIf { it.isNotBlank() },
        substanceCodeSystem = substanceParts.getOrNull(2)?.takeIf { it.isNotBlank() },
        substanceStatus = inv.getOrNull(2)?.takeIf { it.isNotBlank() },
        cellId = inv.getOrElse(5) { "" },
        cellLocation = inv.getOrNull(10)?.takeIf { it.isNotBlank() },
        quantityOnHand = inv.getOrNull(8)?.takeIf { it.isNotBlank() },
        availableQuantity = inv.getOrNull(9)?.takeIf { it.isNotBlank() },
        quantityUnitCode = unitParts.getOrNull(0)?.takeIf { it.isNotBlank() },
        quantityUnitText = unitParts.getOrNull(1)?.takeIf { it.isNotBlank() },
        expirationDate = inv.getOrNull(12)?.takeIf { it.isNotBlank() },
        lotNumber = inv.getOrNull(16)?.takeIf { it.isNotBlank() },
        manufacturerName = inv.getOrNull(17)?.takeIf { it.isNotBlank() },
        supplierName = inv.getOrNull(18)?.takeIf { it.isNotBlank() },
        onOrderQuantity = inv.getOrNull(19)?.takeIf { it.isNotBlank() }
    )
}
