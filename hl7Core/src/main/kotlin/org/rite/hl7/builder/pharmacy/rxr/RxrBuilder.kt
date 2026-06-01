package org.rite.hl7.builder.pharmacy.rxr

import org.rite.hl7.domain.utils.HL7Utils
import org.rite.hl7.domain.utils.HL7Utils.buildComponent
import org.rite.hl7.domain.model.RouteData


/**
 * Builds the HL7 RXR (Pharmacy/Treatment Route) segment.
 * Version-safe for HL7 v2.1 → v2.8
 */
fun buildRXR(
    route: RouteData,
    hl7Version: String = "2.5"
): String {

    /** RXR-1: Route of administration (code ^ text ^ coding system) */
    val routeCode = buildComponent(
        route.routeCode ?: "",
        route.routeText ?: "",
        route.routeCodeSystem ?: ""
    )

    /** RXR-2: Administration site (code ^ text) */
    val adminSite = buildComponent(
        route.adminSiteCode ?: "",
        route.adminSiteText ?: ""
    )

    /** RXR-3: Administration device (code ^ text) */
    val adminDevice = buildComponent(
        route.adminDeviceCode ?: "",
        route.adminDeviceText ?: ""
    )

    /**
     * Canonical RXR fields (RXR-1 → RXR-3)
     */
    val allFields = listOf(
        routeCode,   // RXR-1
        adminSite,   // RXR-2
        adminDevice  // RXR-3
    )

    val maxField = RxrVersionCapabilities.maxField(hl7Version)

    return HL7Utils.buildSegment(
        "RXR",
        *allFields.take(maxField).toTypedArray()
    )
}
