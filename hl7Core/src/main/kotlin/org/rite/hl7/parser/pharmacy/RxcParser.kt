package org.rite.hl7.parser.pharmacy

import org.rite.hl7.domain.model.ComponentData

/**
 * Parses RXC segments and extracts compound medication component details.
 * Each RXC represents a single ingredient or component of a compounded medication.
 */
fun parseComponents(
    segments: Map<String, List<List<String>>>,
    compSep: String
): List<ComponentData> {

    /** Iterate over all RXC segments (multiple components allowed) **/
    return (segments["RXC"] ?: emptyList()).map { rxc ->

        /** Parsed component identifier and name from RXC-2 **/
        val componentParts = rxc.getOrElse(2) { "" }.split(compSep)

        /** Parsed component unit information from RXC-4 **/
        val unitParts = rxc.getOrNull(4)?.split(compSep) ?: emptyList()

        /** Build and return parsed component data **/
        ComponentData(
            componentType = rxc.getOrNull(1)?.takeIf { it.isNotBlank() },
            ndcOrComponentCode = componentParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            componentName = componentParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            componentCodeSystem = componentParts.getOrNull(2)?.takeIf { it.isNotBlank() },
            componentAmount = rxc.getOrNull(3)?.takeIf { it.isNotBlank() },
            componentUnitsCode = unitParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            componentUnitsText = unitParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            componentStrength = rxc.getOrNull(5)?.takeIf { it.isNotBlank() },
            componentStrengthUnits = rxc.getOrNull(6)?.takeIf { it.isNotBlank() }
        )
    }
}
