package org.rite.hl7.parser

import org.rite.hl7.domain.model.AcknowledgmentData
import org.rite.hl7.domain.model.CompleteHL7Message
import org.rite.hl7.domain.model.CustomSegmentData
import org.rite.hl7.domain.model.ErrorData
import org.rite.hl7.domain.model.MessageHeaderData
import org.rite.hl7.domain.model.NoteData
import org.rite.hl7.parser.header.mshParser
import org.rite.hl7.parser.inventory.parseEquipment
import org.rite.hl7.parser.patient.parseVisit
import org.rite.hl7.parser.patient.patientParser
import org.rite.hl7.parser.inventory.parseInventoryItems
import org.rite.hl7.parser.order.parseOrder
import org.rite.hl7.parser.pharmacy.parseComponents
import org.rite.hl7.parser.pharmacy.parseDispenses
import org.rite.hl7.parser.pharmacy.parseMedications
import org.rite.hl7.parser.pharmacy.parseRoutes


//==================== HL7 PARSER ====================//

class Hl7Parser {

    companion object {
        private const val SB: Byte = 0x0B     // SB = Start Block (0x0B)
        private const val EB: Byte = 0x1C    // EB = End Block (0x1C)
        private const val CR: Byte = 0x0D    // CR = Carriage Return (0x0D)

    }


    /*** Parse HL7 message received over (TCP) ***/
    fun parseMllpMessage(mllpWrapped: ByteArray): CompleteHL7Message {

        /** Remove MLLP framing bytes and extract HL7 text**/
        val hl7Content = stripMllpFraming(mllpWrapped)
        return parse(hl7Content)
    }

    /** Parse raw HL7 string into CompleteHL7Message **/
    fun parse(hl7Message: String): CompleteHL7Message {

        if (hl7Message.isBlank()) {
            throw Hl7ParseException("Empty HL7 message")
        }

        /** Normalize line endings and split message into segments **/
        val segments = hl7Message
            .replace("\r\n", "\r")
            .replace("\n", "\r")
            .split("\r")
            .filter { it.isNotBlank() }

        /** Ensure at least one segment exists **/
        if (segments.isEmpty()) {
            throw Hl7ParseException("No segments found in message")
        }

        /** Extract first segment (must be MSH) **/
        val mshSegment = segments.first()

        /** Validate that message starts with MSH segment **/
        if (!mshSegment.startsWith("MSH")) {
            throw Hl7ParseException(
                "First segment must be MSH, found: ${mshSegment.take(10)}"
            )
        }

        /** Parse MSH segment into header object **/
        val header = mshParser(mshSegment)

        /** Group all segments by segment name **/
        val segmentMap = parseAllSegments(segments, header)

        /** Determine component separator from MSH-2 **/
        val compSep = header.encodingCharacters.getOrNull(0)?.toString() ?: "^"

        /** Build final HL7 message model **/
        return CompleteHL7Message(

            /** Unique message identifier **/
            messageId = header.messageControlId,

            /** HL7 message type (e.g. ADT, RDS) **/
            messageType = header.messageType,

            /** HL7 trigger event (e.g. A01, O13) **/
            triggerEvent = header.triggerEvent,

            /** Message creation timestamp **/
            timestamp = header.messageDateTime,

            /** Sending facility identifier **/
            sendingFacility = header.sendingFacility,

            /** Full parsed MSH header **/
            header = header,

            /** Parsed patient information (PID) **/
            patient = patientParser(segmentMap, compSep),

            /** Parsed visit information (PV1) **/
            visit = parseVisit(segmentMap, compSep),

            /** Parsed order information (ORC) **/
            order = parseOrder(segmentMap, compSep),

            /** Parsed medication orders (RXE) **/
            medications = parseMedications(segmentMap, compSep),

            /** Parsed administration routes (RXR) **/
            routes = parseRoutes(segmentMap, compSep),

            /** Parsed compound medication components (RXC) **/
            components = parseComponents(segmentMap, compSep),

            /** Parsed dispense records (RXD) **/
            dispenses = parseDispenses(segmentMap, compSep),


            equipment = parseEquipment(segmentMap, compSep),

            /** Parsed inventory items (INV) - for cycle count messages **/
            inventoryItems = parseInventoryItems(segmentMap, compSep),

            /** Parsed inventory and equipment data (EQU/INV) **/
//            inventory = parseInventory(segmentMap, compSep),

            /** Parsed acknowledgment response (MSA/ERR) **/
            acknowledgment = parseAcknowledgment(segmentMap),

            /** Parsed notes and comments (NTE) **/
            notes = parseNotes(segmentMap),

            /** Parsed custom Z-segments **/
            customSegments = parseCustomSegments(
                segmentMap,
                header.fieldSeparator
            ),

            /** Parsed HL7 errors (ERR) **/
            errors = parseErrors(segmentMap, compSep)
        )
    }

    /** Remove MLLP framing bytes and return raw HL7 message **/
    private fun stripMllpFraming(bytes: ByteArray): String {
        var start = 0
        var end = bytes.size

        /** Skip MLLP start byte if present **/
        if (bytes.isNotEmpty() && bytes[0] == SB) start = 1

        /** Find MLLP end sequence (EB followed by CR) **/
        for (i in start until bytes.size - 1) {
            if (bytes[i] == EB && bytes[i + 1] == CR) {
                end = i
                break
            }
        }
        return bytes.sliceArray(start until end).decodeToString()
    }

    private fun parseAllSegments(
        segments: List<String>,
        header: MessageHeaderData
    ): Map<String, List<List<String>>> {
        val map = mutableMapOf<String, MutableList<List<String>>>()
        val sep = header.fieldSeparator

        segments.forEach { segment ->
            val fields = segment.split(sep)
            val name = fields.first().take(3)
            map.getOrPut(name) { mutableListOf() }.add(fields)
        }
        return map
    }

    /**
     * Parses acknowledgment information from MSA and optional ERR segments.
     * Used for ACK messages and error responses.
     */
    private fun parseAcknowledgment(
        segments: Map<String, List<List<String>>>
    ): AcknowledgmentData? {

        /** Retrieve the first MSA segment; acknowledgment is optional **/
        val msa = segments["MSA"]?.firstOrNull() ?: return null

        /** Extract error condition from ERR if present **/
        val errorCondition = segments["ERR"]
            ?.firstOrNull()
            ?.let { err ->
                err.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: err.getOrNull(2)
            }

        /** Build and return parsed acknowledgment data **/
        return AcknowledgmentData(
            acknowledgmentCode = msa.getOrElse(1) { "" },
            messageControlId = msa.getOrElse(2) { "" },
            textMessage = msa.getOrNull(3)?.takeIf { it.isNotBlank() },
            errorCondition = errorCondition
        )
    }


    /**
     * Parses ERR segments and extracts HL7 and application-level errors.
     * Multiple ERR segments may be present.
     */
    private fun parseErrors(
        segments: Map<String, List<List<String>>>,
        compSep: String
    ): List<ErrorData> {

        /** Iterate over all ERR segments **/
        return (segments["ERR"] ?: emptyList()).map { err ->

            /** Parsed error location components from ERR-2 **/
            val locationParts = err.getOrNull(2)?.split(compSep) ?: emptyList()

            /** Parsed HL7 error code components from ERR-3 **/
            val errorCodeParts = err.getOrNull(3)?.split(compSep) ?: emptyList()

            /** Parsed application error code components from ERR-5 **/
            val appErrorParts = err.getOrNull(5)?.split(compSep) ?: emptyList()

            /** Build and return parsed error data **/
            ErrorData(
                segmentId = locationParts.getOrNull(0)?.takeIf { it.isNotBlank() },
                sequence = locationParts.getOrNull(1)?.takeIf { it.isNotBlank() },
                fieldPosition = locationParts.getOrNull(2)?.takeIf { it.isNotBlank() },
                errorCode = errorCodeParts.getOrNull(0)?.takeIf { it.isNotBlank() },
                errorDescription = errorCodeParts.getOrNull(1)?.takeIf { it.isNotBlank() },
                severity = err.getOrNull(4)?.takeIf { it.isNotBlank() },
                applicationErrorCode = appErrorParts.getOrNull(0)?.takeIf { it.isNotBlank() },
                applicationErrorText = appErrorParts.getOrNull(1)?.takeIf { it.isNotBlank() },
                diagnosticInfo = err.getOrNull(7)?.takeIf { it.isNotBlank() },
                userMessage = err.getOrNull(8)?.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * Parses NTE segments and extracts free-text notes and comments.
     * Notes may apply to the entire message or specific segments.
     */
    private fun parseNotes(
        segments: Map<String, List<List<String>>>
    ): List<NoteData> {

        /** Iterate over all NTE segments **/
        return (segments["NTE"] ?: emptyList()).map { nte ->

            /** Build and return parsed note data **/
            NoteData(
                setId = nte.getOrNull(1)?.takeIf { it.isNotBlank() },
                sourceOfComment = nte.getOrNull(2)?.takeIf { it.isNotBlank() },
                comment = nte.getOrNull(3)?.takeIf { it.isNotBlank() }
                    ?: nte.getOrNull(4)?.takeIf { it.isNotBlank() }
                    ?: "",
                commentType = nte.getOrNull(5)?.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * Parses custom Z-segments (Zxx) and captures all fields dynamically.
     * Preserves vendor-specific or non-standard HL7 extensions.
     */
    private fun parseCustomSegments(
        segments: Map<String, List<List<String>>>,
        fieldSep: String
    ): List<CustomSegmentData> {

        /** Collection of parsed custom segments **/
        val customSegments = mutableListOf<CustomSegmentData>()

        /** Iterate through all segments **/
        segments.forEach { (segmentType, segmentList) ->

            /** Process only Z-segments **/
            if (segmentType.startsWith("Z")) {
                segmentList.forEach { fields ->

                    /** Capture all fields dynamically with field index **/
                    val allFields = fields.drop(1).mapIndexed { index, value ->
                        index + 1 to value
                    }.toMap()

                    /** Build and add parsed custom segment **/
                    customSegments.add(
                        CustomSegmentData(
                            segmentType = segmentType,
                            field1 = fields.getOrNull(1)?.takeIf { it.isNotBlank() },
                            field2 = fields.getOrNull(2)?.takeIf { it.isNotBlank() },
                            field3 = fields.getOrNull(3)?.takeIf { it.isNotBlank() },
                            field4 = fields.getOrNull(4)?.takeIf { it.isNotBlank() },
                            field5 = fields.getOrNull(5)?.takeIf { it.isNotBlank() },
                            field6 = fields.getOrNull(6)?.takeIf { it.isNotBlank() },
                            allFields = allFields
                        )
                    )
                }
            }
        }

        return customSegments
    }
}

// ==================== HELPER FUNCTIONS ====================

fun parseHl7Timestamp(hl7Time: String): String? {
    if (hl7Time.isBlank()) return null
    return try {
        when (hl7Time.length) {
            8 -> "${hl7Time.substring(0, 4)}-${hl7Time.substring(4, 6)}-${hl7Time.substring(6, 8)}"
            12 -> "${hl7Time.substring(0, 4)}-${hl7Time.substring(4, 6)}-${
                hl7Time.substring(6, 8)
            } ${hl7Time.substring(8, 10)}:${hl7Time.substring(10, 12)}:00"

            14 -> "${hl7Time.substring(0, 4)}-${hl7Time.substring(4, 6)}-${
                hl7Time.substring(6, 8)
            } ${hl7Time.substring(8, 10)}:${hl7Time.substring(10, 12)}:${hl7Time.substring(12, 14)}"

            else -> hl7Time
        }
    } catch (_: Exception) {
        hl7Time
    }
}

// ==================== IDEMPOTENCY KEY GENERATOR ====================

/**
 * Generate idempotency key for database unique constraint
 * Recommended: (sending_facility + placer_order_id + order_control)
 */
fun CompleteHL7Message.generateIdempotencyKey(): String {
    val facility = sendingFacility
    val orderId = order?.placerOrderId ?: messageId
    val control = order?.orderControl ?: "UNKNOWN"
    return "${facility}_${orderId}_${control}"
}

/**
 * Alternative idempotency key using message control ID
 * Use when the same order might be sent multiple times with different control codes
 */
fun CompleteHL7Message.generateMessageIdempotencyKey(): String {
    return "${sendingFacility}_${messageId}"
}


fun CompleteHL7Message.generateInventoryIdempotencyKey(): String {
    val facility = sendingFacility
    val equipmentId = equipment?.equipmentId ?: "UNKNOWN"
    val timestamp = this.timestamp
    return "${facility}_${equipmentId}_${timestamp}"
}