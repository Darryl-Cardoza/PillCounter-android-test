package org.rite.hl7.domain.model

// ==================== COMPONENT / NDC (RXC) ====================
// Parsed representation of the HL7 RXC segment.
// Contains individual components or NDC-level details for compounded medications.

data class ComponentData(

    /** Parsed component type indicator (e.g., A=Additive, B=Base) (RXC-1) **/
    val componentType: String? = null,

    /** Parsed NDC or component code used for barcode validation (RXC-2.1) **/
    val ndcOrComponentCode: String? = null,

    /** Parsed component or substance name (RXC-2.2) **/
    val componentName: String? = null,

    /** Parsed coding system for component identifier (typically NDC) (RXC-2.3) **/
    val componentCodeSystem: String? = null,

    /** Parsed amount of this component in the compound (RXC-3) **/
    val componentAmount: String? = null,

    /** Parsed unit code for component amount (RXC-4.1) **/
    val componentUnitsCode: String? = null,

    /** Parsed unit text for component amount (RXC-4.2) **/
    val componentUnitsText: String? = null,

    /** Parsed strength or concentration of the component (RXC-5) **/
    val componentStrength: String? = null,

    /** Parsed units for component strength (RXC-6) **/
    val componentStrengthUnits: String? = null
)
