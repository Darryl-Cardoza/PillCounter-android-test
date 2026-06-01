package org.rite.hl7.domain.model

// ==================== ORDER (ORC) ====================
// Parsed representation of the HL7 ORC segment.
// Contains order control, identifiers, and ordering provider information.

data class OrderData(

    /** Parsed order control code driving order workflow (ORC-1) **/
    val orderControl: String,

    /** Parsed placer order identifier used as primary business ID (ORC-2.1) **/
    val placerOrderId: String,

    /** Parsed namespace for placer order identifier (ORC-2.2) **/
    val placerOrderNamespace: String? = null,

    /** Parsed filler order identifier assigned by receiving system (ORC-3.1) **/
    val fillerOrderId: String? = null,

    /** Parsed namespace for filler order identifier (ORC-3.2) **/
    val fillerOrderNamespace: String? = null,

    /** Parsed current status of the order (ORC-5) **/
    val orderStatus: String? = null,

    /** Parsed date and time the order was created or last updated (ORC-9) **/
    val orderDateTime: String? = null,

    /** Parsed ordering provider identifier (ORC-12.1) **/
    val orderingProviderId: String? = null,

    /** Parsed ordering provider family name (ORC-12.2) **/
    val orderingProviderFamilyName: String? = null,

    /** Parsed ordering provider given name (ORC-12.3) **/
    val orderingProviderGivenName: String? = null,

    /** Parsed facility that originated the order (ORC-21) **/
    val orderingFacility: String? = null
)
