package org.rite.hl7.builder.inventory

import org.rite.hl7.domain.model.InventoryData
import org.rite.hl7.domain.utils.HL7Utils
import org.rite.hl7.domain.utils.HL7Utils.buildComponent



fun buildEQU(
    inventory: InventoryData,
    hl7Version: String = "2.5"
): String {

    // EQU-1: Equipment Instance Identifier
    val equipmentId = buildComponent(

    )

    /**
     * Canonical EQU fields (EQU-1 → EQU-6)
     */
    val allFields = listOf(
        equipmentId,
    )

    val maxField = EquVersionCapabilities.maxField(hl7Version)

    return HL7Utils.buildSegment(
        "EQU",
        *allFields.take(maxField).toTypedArray()
    )
}


object EquVersionCapabilities {
    fun maxField(version: String): Int = 6
}
