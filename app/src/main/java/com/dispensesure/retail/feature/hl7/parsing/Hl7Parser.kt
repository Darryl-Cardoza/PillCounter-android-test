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

    fun map(message: HL7Message): Result<List<OrderGroup>> {
        val msh: MSHSegment? = message.header
        val msgControlId = msh?.messageControlId.orEmpty()
        val allSegments = message.typedSegments
        if (allSegments.none { it.segmentName == ORCSegment.NAME }) {
            return Result.failure(IllegalArgumentException("No ORC segment in message $msgControlId"))
        }

        // Pair each ORC with the RXE immediately following it in wire order.
        val orders = buildList {
            var pendingOrc: ORCSegment? = null
            for (seg in allSegments) {
                when (seg.segmentName) {
                    ORCSegment.NAME -> {
                        pendingOrc?.let { orc ->
                            val control = orc.orderControl.trim().uppercase()
                            if (control !in setOf("NW", "RF")) {
                                add(mapOrderGroup(msh, orc, null))
                            } else {
                                logger.w("ORC control=$control has no paired RXE in msgId=$msgControlId — skipping")
                            }
                        }
                        @Suppress("UNCHECKED_CAST")
                        pendingOrc = seg as ORCSegment
                    }
                    RXESegment.NAME -> {
                        val orc = pendingOrc ?: continue
                        @Suppress("UNCHECKED_CAST")
                        add(mapOrderGroup(msh, orc, seg as RXESegment))
                        pendingOrc = null
                    }
                }
            }
            // Flush trailing ORC with no RXE
            pendingOrc?.let { orc ->
                val control = orc.orderControl.trim().uppercase()
                if (control !in setOf("NW", "RF")) {
                    add(mapOrderGroup(msh, orc, null))
                } else {
                    logger.w("ORC control=$control has no paired RXE in msgId=$msgControlId — skipping")
                }
            }
        }

        if (orders.isEmpty()) return Result.failure(IllegalArgumentException("No processable ORC/RXE pairs in message $msgControlId"))
        return Result.success(orders)
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
