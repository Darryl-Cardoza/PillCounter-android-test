package org.rite.hl7.builder.pharmacy.rxc

import org.rite.hl7.builder.pharmacy.rxc.RxcVersionCapabilities
import org.rite.hl7.domain.model.ComponentData
import org.rite.hl7.domain.utils.HL7Utils
import org.rite.hl7.domain.utils.HL7Utils.buildComponent



/**
 * Builds the HL7 RXC (Pharmacy/Treatment Component) segment.
 * Version-safe for HL7 v2.1 → v2.8
 */
fun buildRXC(
    component: ComponentData,
    hl7Version: String = "2.5"
): String {

    /** RXC-2: Component code (identifier ^ text ^ coding system) */
    val componentCode = buildComponent(
        component.ndcOrComponentCode ?: "",
        component.componentName ?: "",
        component.componentCodeSystem ?: ""
    )

    /** RXC-4: Component units (code ^ text) */
    val componentUnits = buildComponent(
        component.componentUnitsCode ?: "",
        component.componentUnitsText ?: ""
    )

    /**
     * Canonical RXC field list (RXC-1 → RXC-6)
     */
    val allFields = listOf(
        component.componentType ?: "",        // RXC-1
        componentCode,                        // RXC-2
        component.componentAmount ?: "",      // RXC-3
        componentUnits,                       // RXC-4
        component.componentStrength ?: "",    // RXC-5
        component.componentStrengthUnits ?: ""// RXC-6
    )

    val maxField = RxcVersionCapabilities.maxField(hl7Version)

    return HL7Utils.buildSegment(
        "RXC",
        *allFields.take(maxField).toTypedArray()
    )
}

