package com.dispensesure.retail.feature.hl7.parsing

import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.feature.hl7.parsing.model.Hl7OrderAction
import com.dispensesure.retail.feature.hl7.parsing.model.OrderGroup
import org.rite.hl7.model.HL7Message
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Hl7OrderHandler @Inject constructor() {

    private val logger = AppLogger("Hl7OrderHandler")

    fun handle(order: OrderGroup, message: HL7Message): Hl7OrderAction = when (order.orderControl) {
        "NW" -> Hl7OrderAction.NewOrder(order, message)
        "RF" -> Hl7OrderAction.Refill(order, message)
        "CA" -> Hl7OrderAction.Cancel(
            rxNo = order.fillerOrderNumber.takeIf { it.isNotBlank() }
                ?: order.placerOrderNumber
        )
        "HD" -> Hl7OrderAction.Hold(order.placerOrderNumber)
        "RL" -> Hl7OrderAction.Release(order.placerOrderNumber)
        "DC" -> Hl7OrderAction.Discontinue(order.placerOrderNumber)
        "RP" -> {
            // TODO: RP (Replace) atomicity and dual-ACK behaviour not yet designed
            logger.w("RP (Replace) received for ${order.placerOrderNumber} — not yet implemented, ignoring")
            Hl7OrderAction.ReplaceTodo
        }
        else -> {
            // Should never reach here — library validator rejects unknown ORC-1 codes
            logger.e("Unhandled ORC-1 code: ${order.orderControl} for ${order.placerOrderNumber}")
            Hl7OrderAction.ReplaceTodo
        }
    }
}
