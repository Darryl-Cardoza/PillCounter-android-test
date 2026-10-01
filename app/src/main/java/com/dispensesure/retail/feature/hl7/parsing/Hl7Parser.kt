package com.dispensesure.retail.feature.hl7.parsing

import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.feature.hl7.parsing.model.OrderGroup
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.MSHSegment
import org.rite.hl7.model.segment.ORCSegment
import org.rite.hl7.model.segment.RXESegment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Hl7Parser @Inject constructor() {

    private val logger = AppLogger("Hl7Parser")

    fun map(message: HL7Message): List<OrderGroup> {
        val msh: MSHSegment? = message.header
        val msgControlId = msh?.messageControlId.orEmpty()
        val orc = message.segment<ORCSegment>(ORCSegment.NAME)
            ?: error("No ORC segment in message $msgControlId")
        return listOf(mapOrderGroup(msh, orc, message.segment(RXESegment.NAME)))
    }

    private fun mapOrderGroup(
        msh: MSHSegment?,
        orc: ORCSegment,
        rxe: RXESegment?,
        resolvedPriorityCode: String? = null,
    ): OrderGroup {
        val placerOrderNumber = orc.placerOrderNumber.trim()
        val fillerOrderNumber = orc.fillerOrderNumber.trim()

        val priorityCode = resolvedPriorityCode?.trim()
            ?: orc.quantityTiming.priority?.trim()

        val priority = mapPriority(priorityCode)

        val numberOfRefills = rxe?.numberOfRefills?.trim()?.toIntOrNull()
        // RXE-16 = pending refills remaining — via raw AST field access
        val pendingRefills = rxe?.raw?.fieldValue(16)?.trim()?.toIntOrNull()

        val refillNumber = when {
            numberOfRefills != null && pendingRefills != null -> numberOfRefills - pendingRefills
            else -> 0
        }

        return OrderGroup(
            messageControlId = msh?.messageControlId.orEmpty(),
            sendingApplication = msh?.sendingApplication.orEmpty(),
            sendingFacility = msh?.sendingFacility.orEmpty(),
            receivingApplication = msh?.receivingApplication.orEmpty(),
            receivingFacility = msh?.receivingFacility.orEmpty(),
            messageDateTime = msh?.dateTimeOfMessage.orEmpty(),
            orderControl = orc.orderControl.trim(),
            placerOrderNumber = placerOrderNumber,
            fillerOrderNumber = fillerOrderNumber,
            orderStatus = orc.orderStatus.trim().takeIf { it.isNotBlank() },
            priority = priority,
            giveCode = rxe?.giveCode?.trim()?.takeIf { it.isNotBlank() },
            giveName = rxe?.giveName?.trim()?.takeIf { it.isNotBlank() },
            dispenseAmount = rxe?.dispenseAmount?.trim()?.toDoubleOrNull()?.toInt()
                ?: rxe?.giveAmountMinimum?.trim()?.toDoubleOrNull()?.toInt(),
            giveUnits = rxe?.giveUnitsCode?.trim()?.takeIf { it.isNotBlank() }
                ?: rxe?.giveUnitsText?.trim()?.takeIf { it.isNotBlank() },
            substitutionStatus = rxe?.raw?.fieldValue(9)?.trim()?.takeIf { it.isNotBlank() },
            numberOfRefills = numberOfRefills,
            prescriptionNumber = rxe?.prescriptionNumber?.trim()?.takeIf { it.isNotBlank() }
                ?: fillerOrderNumber.takeIf { it.isNotBlank() },
            pendingRefills = pendingRefills,
            refillNumber = refillNumber,
        )
    }

    private fun mapPriority(code: String?): TxnPriority {
        return when (code?.trim()?.uppercase()) {
            "S", "A" -> TxnPriority.High
            "R"       -> TxnPriority.Medium
            "T"       -> TxnPriority.Low
            null, ""  -> TxnPriority.Medium
            else      -> {
                logger.w("Unknown ORC-7.6 priority code '$code' — treating as Medium")
                TxnPriority.Medium
            }
        }
    }
}
