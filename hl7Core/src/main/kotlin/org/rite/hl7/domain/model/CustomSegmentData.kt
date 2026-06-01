package org.rite.hl7.domain.model


// ==================== NOTES (NTE) ====================
// Parsed representation of the HL7 NTE segment.
// Stores free-text notes or comments associated with a message or segment.

data class NoteData(

    /** Parsed note set identifier (NTE-1) **/
    val setId: String? = null,

    /** Parsed source of the comment (NTE-2) **/
    val sourceOfComment: String? = null,

    /** Parsed note or comment text (NTE-3) **/
    val comment: String,

    /** Parsed note type or classification (NTE-4) **/
    val commentType: String? = null
)



// ==================== CUSTOM SEGMENTS (Zxx) ====================
// Parsed representation of non-standard HL7 Z-segments.
// Preserves vendor-specific extensions and custom data.

data class CustomSegmentData(

    /** Parsed custom segment name (e.g., ZDS, ZRX, ZINV) **/
    val segmentType: String,

    /** Parsed custom segment field 1 **/
    val field1: String? = null,

    /** Parsed custom segment field 2 **/
    val field2: String? = null,

    /** Parsed custom segment field 3 **/
    val field3: String? = null,

    /** Parsed custom segment field 4 **/
    val field4: String? = null,

    /** Parsed custom segment field 5 **/
    val field5: String? = null,

    /** Parsed custom segment field 6 **/
    val field6: String? = null,

    /** Parsed dynamic map of all custom segment fields (fieldIndex → value) **/
    val allFields: Map<Int, String> = emptyMap()
)
