package org.rite.hl7.domain.model


// ==================== ROUTE (RXR) ====================
// Parsed representation of the HL7 RXR segment.
// Defines how and where a medication is administered.

data class RouteData(

    /** Parsed medication administration route code (RXR-1.1) **/
    val routeCode: String? = null,

    /** Parsed medication administration route text (RXR-1.2) **/
    val routeText: String? = null,

    /** Parsed coding system for route identifier (RXR-1.3) **/
    val routeCodeSystem: String? = null,

    /** Parsed administration site code (RXR-2.1) **/
    val adminSiteCode: String? = null,

    /** Parsed administration site text (RXR-2.2) **/
    val adminSiteText: String? = null,

    /** Parsed administration device code (RXR-3.1) **/
    val adminDeviceCode: String? = null,

    /** Parsed administration device text (RXR-3.2) **/
    val adminDeviceText: String? = null
)
