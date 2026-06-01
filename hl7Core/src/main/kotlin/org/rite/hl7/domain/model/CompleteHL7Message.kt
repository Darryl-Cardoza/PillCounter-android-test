package org.rite.hl7.domain.model

/**
 * Main HL7 message container holding all parsed segments
 */
data class CompleteHL7Message(

    /** MSH-10: Unique message control ID used as primary key **/
    val messageId: String,

    /** MSH-9.1: HL7 message type (e.g. ADT, ORM, RDS) **/
    val messageType: String,

    /** MSH-9.2: HL7 trigger event (e.g. A01, O13) **/
    val triggerEvent: String,

    /** MSH-7: Date and time when message was created **/
    val timestamp: String,

    /** MSH-4: Sending facility identifier (used for idempotency) **/
    val sendingFacility: String,

    /** Parsed MSH header containing message metadata **/
    val header: MessageHeaderData,

    /** PID: Patient demographic information **/
    val patient: PatientData? = null,

    /** PV1: Patient visit and encounter details **/
    val visit: VisitData? = null,

    /** ORC: Order control and order identifiers **/
    val order: OrderData? = null,

    /** RXE: Medication order details **/
    val medications: List<MedicationData> = emptyList(),

    /** RXR: Medication administration routes **/
    val routes: List<RouteData> = emptyList(),

    /** RXC: Components for compound medications **/
    val components: List<ComponentData> = emptyList(),

    /** RXD: Medication dispensing records **/
    val dispenses: List<DispenseData> = emptyList(),

    /** EQU: Equipment detail for inventory messages **/
    val equipment: EquipmentData? = null,

    /** INV: Inventory detail segments (for INR^U06 requests and INU^U05 updates) **/
    val inventoryItems: List<InventoryItemData> = emptyList(),

    /** EQU + INV: Inventory and equipment status **/
    val inventory: InventoryData? = null,

    /** MSA + ERR: HL7 acknowledgment response **/
    val acknowledgment: AcknowledgmentData? = null,

    /** NTE: Free-text notes and comments **/
    val notes: List<NoteData> = emptyList(),

    /** Z-segments: Custom non-standard HL7 segments **/
    val customSegments: List<CustomSegmentData> = emptyList(),


    /** OBX HL7 segments **/
    val obxSegments: List<ObservationData> = emptyList(),



    /** ERR: HL7 processing and validation errors **/
    val errors: List<ErrorData> = emptyList()
)
