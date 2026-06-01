package org.rite.hl7.parser.order

import org.rite.hl7.domain.model.OrderData

/**
 * Parses the ORC segment and extracts order-level information.
 * ORC defines order control, identifiers, and ordering provider details.
 */
fun parseOrder(
    segments: Map<String, List<List<String>>>,
    compSep: String
): OrderData? {

    /** Retrieve the first ORC segment; order information is optional **/
    val orc = segments["ORC"]?.firstOrNull() ?: return null

    /** Parsed placer order number components from ORC-2 **/
    val placerParts = orc.getOrElse(2) { "" }.split(compSep)

    /** Parsed filler order number components from ORC-3 **/
    val fillerParts = orc.getOrNull(3)?.split(compSep) ?: emptyList()

    /** Parsed ordering provider components from ORC-12 **/
    val providerParts = orc.getOrNull(12)?.split(compSep) ?: emptyList()

    /** Build and return parsed order data **/
    return OrderData(
        orderControl = orc.getOrElse(1) { "" },
        placerOrderId = placerParts.getOrNull(0) ?: "",
        placerOrderNamespace = placerParts.getOrNull(1)?.takeIf { it.isNotBlank() },
        fillerOrderId = fillerParts.getOrNull(0)?.takeIf { it.isNotBlank() },
        fillerOrderNamespace = fillerParts.getOrNull(1)?.takeIf { it.isNotBlank() },
        orderStatus = orc.getOrNull(5)?.takeIf { it.isNotBlank() }
            ?: orc.getOrNull(6)?.takeIf { it.isNotBlank() },
        orderDateTime = orc.getOrNull(9)?.takeIf { it.isNotBlank() },
        orderingProviderId = providerParts.getOrNull(0)?.takeIf { it.isNotBlank() },
        orderingProviderFamilyName = providerParts.getOrNull(1)?.takeIf { it.isNotBlank() },
        orderingProviderGivenName = providerParts.getOrNull(2)?.takeIf { it.isNotBlank() },
        orderingFacility = orc.getOrNull(21)?.takeIf { it.isNotBlank() }
    )
}
