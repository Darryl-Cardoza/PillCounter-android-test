package org.rite.hl7.model

import org.rite.hl7.model.segment.ORCSegment
import org.rite.hl7.model.segment.ZADSegment

/**
 * Business classification of a parsed message, derived from message type,
 * trigger event, ORC-1, and payload.
 */
enum class HL7MessageKind {
    DISPENSE,            // RDS^O13 (or RDS^O01 pre-2.5) with ORC-1=RE
    DISPENSE_ORDER,      // RDE^O11 (or RDE^O01 pre-2.5)
    CANCEL_ORDER,        // ORC-1=CA on any type
    INVENTORY_RESPONSE,  // INR^U05
    INVENTORY_ADJUSTMENT,// INR^U06 carrying ZAD
    INVENTORY_REQUEST,   // INR^U06 without ZAD (count request)
    INVENTORY_UPDATE,    // INU^U05
    QUERY,               // QBP^Q11
    QUERY_RESPONSE,      // RSP^K11
    ACKNOWLEDGMENT,      // ACK
    UNKNOWN;

    companion object {
        fun from(message: HL7Message): HL7MessageKind {
            val type = message.messageCode.uppercase()
            val trigger = message.triggerEvent.uppercase()
            val control = message.segment<ORCSegment>(ORCSegment.NAME)?.orderControl?.uppercase()

            return when {
                control == "CA" -> CANCEL_ORDER
                type == "RDS" && (trigger == "O13" || trigger == "O01" || trigger == "013" || trigger == "001") -> DISPENSE
                type == "RDE" && (trigger == "O11" || trigger == "O01" || trigger == "011" || trigger == "001") -> DISPENSE_ORDER
                type == "INR" && trigger == "U05" -> INVENTORY_RESPONSE
                type == "INR" && trigger == "U06" ->
                    if (message.segmentNamed(ZADSegment.NAME) != null) INVENTORY_ADJUSTMENT
                    else INVENTORY_REQUEST
                type == "INU" && trigger == "U05" -> INVENTORY_UPDATE
                type == "QBP" && trigger == "Q11" -> QUERY
                type == "RSP" && trigger == "K11" -> QUERY_RESPONSE
                type == "ACK" -> ACKNOWLEDGMENT
                else -> UNKNOWN
            }
        }
    }
}
