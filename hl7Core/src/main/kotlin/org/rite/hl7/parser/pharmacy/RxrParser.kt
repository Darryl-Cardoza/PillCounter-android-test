package org.rite.hl7.parser.pharmacy

import org.rite.hl7.domain.model.RouteData

/**
 * Parses RXR segments and extracts medication administration route details.
 * Each RXR defines how and where a medication is administered.
 */
fun parseRoutes(
    segments: Map<String, List<List<String>>>,
    compSep: String
): List<RouteData> {

    /** Iterate over all RXR segments (multiple routes allowed) **/
    return (segments["RXR"] ?: emptyList()).map { rxr ->

        /** Parsed route code and description from RXR-1 **/
        val routeParts = rxr.getOrElse(1) { "" }.split(compSep)

        /** Parsed administration site from RXR-2 **/
        val siteParts = rxr.getOrNull(2)?.split(compSep) ?: emptyList()

        /** Parsed administration device from RXR-3 **/
        val deviceParts = rxr.getOrNull(3)?.split(compSep) ?: emptyList()

        /** Build and return parsed route data **/
        RouteData(
            routeCode = routeParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            routeText = routeParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            routeCodeSystem = routeParts.getOrNull(2)?.takeIf { it.isNotBlank() },
            adminSiteCode = siteParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            adminSiteText = siteParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            adminDeviceCode = deviceParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            adminDeviceText = deviceParts.getOrNull(1)?.takeIf { it.isNotBlank() }
        )
    }
}
