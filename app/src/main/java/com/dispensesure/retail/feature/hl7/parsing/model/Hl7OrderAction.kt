package com.dispensesure.retail.feature.hl7.parsing.model

import org.rite.hl7.model.HL7Message

sealed class Hl7OrderAction {
    data class NewOrder(val order: OrderGroup, val message: HL7Message) : Hl7OrderAction()
    data class Refill(val order: OrderGroup, val message: HL7Message) : Hl7OrderAction()
    data class Cancel(val orderId: String) : Hl7OrderAction()
    data class Hold(val orderId: String) : Hl7OrderAction()
    data class Release(val orderId: String) : Hl7OrderAction()
    data class Discontinue(val orderId: String) : Hl7OrderAction()
    data class ChangeOrder(val order: OrderGroup) : Hl7OrderAction()
    // TODO: RP (Replace) design deferred — atomicity and dual-ACK behaviour not yet decided
    object ReplaceTodo : Hl7OrderAction()
}
