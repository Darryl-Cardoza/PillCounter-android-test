package org.rite.hl7.builder.inventory

import org.rite.hl7.domain.model.InventoryBinData
import org.rite.hl7.domain.utils.HL7Utils
import org.rite.hl7.domain.utils.HL7Utils.buildComponent

fun buildINV(
    bin: InventoryBinData,
    hl7Version: String = "2.5"
): String {

    // INV-1: Substance Identifier
    val substanceId = buildComponent(
        bin.substanceId,
        bin.substanceName ?: "",
        bin.substanceCodeSystem ?: ""
    )

    // INV-11: Quantity Units
    val quantityUnits = buildComponent(
        bin.quantityUnitCode ?: "",
        bin.quantityUnitText ?: ""
    )

    /**
     * Canonical INV fields (INV-1 → INV-19)
     */
    val allFields = listOf(
        substanceId,                 // INV-1
        bin.substanceStatus ?: "",   // INV-2
        "",                          // INV-3
        "",                          // INV-4
        bin.cellId ?: "",            // INV-5
        "",                          // INV-6
        "",                          // INV-7
        bin.quantityOnHand ?: "",    // INV-8
        bin.availableQuantity ?: "", // INV-9
        bin.cellLocation ?: "",      // INV-10
        quantityUnits,               // INV-11
        bin.expirationDate ?: "",    // INV-12
        "",                          // INV-13
        "",                          // INV-14
        "",                          // INV-15
        bin.lotNumber ?: "",         // INV-16
        bin.manufacturerName ?: "",  // INV-17
        bin.supplierName ?: "",      // INV-18
        bin.onOrderQuantity ?: ""    // INV-19
    )

    val maxField = InvVersionCapabilities.maxField(hl7Version)

    return HL7Utils.buildSegment(
        "INV",
        *allFields.take(maxField).toTypedArray()
    )
}




object InvVersionCapabilities {

    fun maxField(version: String): Int =
        when {
            version.startsWith("2.1") -> 11
            version.startsWith("2.2") -> 11
            version.startsWith("2.3") -> 12
            version.startsWith("2.4") -> 16
            else -> 19 // 2.5+
        }
}
