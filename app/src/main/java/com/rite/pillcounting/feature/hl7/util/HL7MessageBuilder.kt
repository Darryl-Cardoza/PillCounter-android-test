package com.rite.pillcounting.feature.hl7.util


import android.os.Build
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import org.rite.hl7.builder.HL7MessageBuilder as Hl7Builder
import org.rite.hl7.domain.model.CompleteHL7Message
import org.rite.hl7.domain.model.CustomSegmentData
import org.rite.hl7.domain.model.DispenseData
import org.rite.hl7.domain.model.MessageHeaderData
import org.rite.hl7.domain.model.NoteData
import org.rite.hl7.domain.model.ObservationData
import org.rite.hl7.domain.model.OrderData
import org.rite.hl7.domain.model.PatientData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * =========================================================
 * HL7MessageBuilder
 * =========================================================
 *
 * Responsibility:
 * - Build HL7 messages (RDS / INU)
 *
 * This class:
 * - Does NOT send messages
 * - Does NOT access database
 * - Does NOT use coroutines
 */
object HL7MessageBuilder {

    private const val IMAGE_PORT = 8443

    /* =========================================================
     * DISPENSE (RDS O13)
     * ========================================================= */

    @Suppress("SimpleDateFormat")
    fun buildDispenseMessage(
        txn: PillCountTxnEntity,
        txnDetails: List<PillCountTxnDetailsEntity>,
        drugCode: String,
        drugName: String,
        pharmacistId: String?,
        pharmacistName: String?,
        location: String?
    ): CompleteHL7Message {

        val now = now()
        val totalCount = txnDetails.sumOf { it.pillCount ?: 0 }

        val imageObx = buildImageObx(
            txn,
            txnDetails,
            observationId = "DISP_IMG",
            label = "Dispense Image"
        )

        return CompleteHL7Message(
            messageId = System.currentTimeMillis().toString(),
            messageType = "RDS",
            triggerEvent = "O13",
            timestamp = now,
            sendingFacility = "PillCounter-${Build.MODEL}",

            header = buildHeader("RDS", "O13", now),

            patient = PatientData(
                patientId = txn.rxNo ?: txn.txnId.toString()
            ),

            order = OrderData(
                orderControl = "RE",
                orderStatus = "CM",
                placerOrderId = txn.rxNo ?: txn.txnId.toString()
            ),

            dispenses = listOf(
                DispenseData(
                    dispenseSubId = "1",
                    drugCode = drugCode,
                    drugName = drugName,
                    drugCodeSystem = "NDC",
                    dateTimeDispensed = now,
                    quantityDispensed = totalCount.toString(),
                    unitCode = "TAB",
                    unitText = "Tablets",
                    prescriptionNumber = txn.rxNo ?: txn.txnId.toString(),
                    pharmacistId = pharmacistId,
                    pharmacistGivenName = pharmacistName,
                    deliverToLocation = location,
                    dispensingNotes = txn.note,
                    lotNumber = txn.lotNo,
                    expirationDate = txn.expiry
                )
            ),

            obxSegments = imageObx,

            notes = buildCommonNotes(txn, totalCount)
        )
    }


    fun buildInventoryMessage(
        batch: BatchEntity,
        txns: List<BatchTxnDto>,
        requestId: String = batch.requestIdFromPMS
            ?.takeIf { it.isNotBlank() }
            ?: "REQ${batch.batchId}",
        orderId: String = batch.bucketId.orEmpty()
    ): String {

        data class Key(val ndc: String, val name: String, val lot: String, val expiry: String)
        data class Qty(var opened: Int = 0, var sealed: Int = 0)

        val now = System.currentTimeMillis()
        val messageId = "RES${now / 1000}"

        val grouped = linkedMapOf<Key, Qty>()
        txns.forEach { txn ->
            val key = Key(
                ndc = txn.ndc.orEmpty(),
                name = txn.drugName.orEmpty(),
                lot = txn.lotNo.orEmpty(),
                expiry = txn.expiry.orEmpty()
            )
            val packageQty = txn.packageQty ?: 0
            val existing = grouped.getOrPut(key) { Qty() }
            existing.opened += txn.looseQty ?: 0
            existing.sealed += (txn.bottleQty ?: 0) * packageQty
        }

        val segments = mutableListOf<CustomSegmentData>()

        segments.add(CustomSegmentData(
            segmentType = "MSA",
            allFields = mapOf(1 to "AA", 2 to requestId)
        ))
        segments.add(CustomSegmentData(
            segmentType = "ORC",
            allFields = mapOf(1 to "RE", 2 to orderId)
        ))

        grouped.entries.forEachIndexed { idx, (key, value) ->
            val setId = (idx + 1).toString()
            val total = (value.opened + value.sealed).toString()

            segments.add(CustomSegmentData(
                segmentType = "INV",
                allFields = mapOf(
                    1 to setId,
                    2 to "${key.ndc}^${key.name}",
                    3 to "", 4 to "", 5 to "", 6 to "",
                    7 to "", 8 to "", 9 to "", 10 to "",
                    11 to total,
                    12 to "", 13 to "", 14 to "", 15 to ""
                )
            ))

            if (value.opened == 0 && value.sealed == 0) {
                segments.add(CustomSegmentData(segmentType = "ZIN", field1 = setId, field2 = "NA", field3 = "0", field4 = "", field5 = ""))
            } else {
                if (value.opened > 0) {
                    segments.add(CustomSegmentData(segmentType = "ZIN", field1 = setId, field2 = "OPENED", field3 = value.opened.toString(), field4 = key.lot, field5 = key.expiry))
                }
                if (value.sealed > 0) {
                    segments.add(CustomSegmentData(segmentType = "ZIN", field1 = setId, field2 = "SEALED", field3 = value.sealed.toString(), field4 = key.lot, field5 = key.expiry))
                }
            }
        }

        val message = CompleteHL7Message(
            messageId = messageId,
            messageType = "INR",
            triggerEvent = "U05",
            timestamp = now.toString(),
            sendingFacility = "STORE",
            header = MessageHeaderData(
                fieldSeparator = "|",
                encodingCharacters = "^~\\&",
                sendingApplication = "PILLCOUNTER",
                sendingFacility = "STORE",
                receivingApplication = "PMS",
                receivingFacility = "PHARMACY",
                messageType = "INR",
                triggerEvent = "U05",
                messageControlId = messageId,
                processingId = "P",
                versionId = "2.5",
                messageDateTime = now.toString()
            ),
            customSegments = segments
        )

        return Hl7Builder().build(message)
    }

    private fun buildImageObx(
        txn: PillCountTxnEntity,
        details: List<PillCountTxnDetailsEntity>,
        observationId: String,
        label: String
    ): List<ObservationData> {

        val detailObxList = details.mapIndexed { index, detail ->

            val count = detail.pillCount ?: 0
            val type = detail.type ?: "UNKNOWN"
            val fileName = detail.imagePath?.substringAfterLast("/") ?: ""

            ObservationData(
                setId = (index + 1).toString(),
                valueType = "ST",
                observationId = observationId,
                observationText = "$label ${index + 1}",
                observationValue = "count=$count|type=$type|image=$fileName",
                resultStatus = "F"
            )
        }

        val barcodeImagePath = txn.barcodeImage
        val barcodeFileName = barcodeImagePath?.substringAfterLast("/") ?: ""
        val barcodeType = StepState.SCAN

        val barcodeObx = if (barcodeFileName.isNotEmpty()) {
            ObservationData(
                setId = (detailObxList.size + 1).toString(),
                valueType = "ST",
                observationId = observationId,
                observationText = "Barcode Image",
                observationValue = "count=0|type=$barcodeType|image=$barcodeFileName",
                resultStatus = "F"
            )
        } else {
            null
        }

        return if (barcodeObx != null) {
            detailObxList + barcodeObx
        } else {
            detailObxList
        }
    }

    private fun buildHeader(type: String, trigger: String, time: String) =
        MessageHeaderData(
            fieldSeparator = "|",
            encodingCharacters = "^~\\&",
            sendingApplication = "PillCounter",
            sendingFacility = "ROBOT",
            receivingApplication = "PMS",
            receivingFacility = "PHARMACY",
            messageType = type,
            triggerEvent = trigger,
            messageControlId = System.currentTimeMillis().toString(),
            processingId = "P",
            versionId = "2.5",
            messageDateTime = time
        )

    private fun buildCommonNotes(
        txn: PillCountTxnEntity,
        totalCount: Int
    ) = listOf(
        NoteData("1", "L", "Transaction completed"),
        NoteData("2", "L", "Total Count: $totalCount"),
        NoteData("3", "L", "Transaction Id: ${txn.txnId}"),
        NoteData("4", "L", "Note: ${txn.note}")
    )

    private fun now(): String =
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())

    private fun buildRoomImageUrl(deviceIp: String, fileName: String, port: String): String {
        return "https://$deviceIp:$port/images/$fileName"
    }
}