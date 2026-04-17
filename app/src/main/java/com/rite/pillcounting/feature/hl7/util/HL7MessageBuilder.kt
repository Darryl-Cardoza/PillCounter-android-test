package com.rite.pillcounting.feature.hl7.util


import android.os.Build
import com.rite.pillcounting.core.hl7.hl7MessageHandler.domain.model.ObservationData
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import org.rite.hl7.hl7.domain.model.CompleteHL7Message
import org.rite.hl7.hl7.domain.model.DispenseData
import org.rite.hl7.hl7.domain.model.MessageHeaderData
import org.rite.hl7.hl7.domain.model.NoteData
import org.rite.hl7.hl7.domain.model.OrderData
import org.rite.hl7.hl7.domain.model.PatientData
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
        requestId: String = batch.requestIdFromPMS.orEmpty(),
        orderId: String = batch.bucketId.orEmpty()
    ): String {

        data class Key(
            val ndc: String,
            val name: String,
            val lot: String,
            val expiry: String
        )

        data class Qty(
            var opened: Int = 0,
            var sealed: Int = 0
        )

        val now = System.currentTimeMillis()
        val messageId = "RES${System.currentTimeMillis() / 1000}"

        val grouped = linkedMapOf<Key, Qty>()

        txns.forEach { txn ->
            val ndc = txn.ndc.orEmpty()
            val name = txn.drugName.orEmpty()
            val lot = txn.lotNo.orEmpty()
            val expiry = txn.expiry.orEmpty()

            val packageQty = txn.packageQty ?: 0
            val opened = txn.looseQty ?: 0
            val sealed = (txn.bottleQty ?: 0) * packageQty

            val key = Key(
                ndc = ndc,
                name = name,
                lot = lot,
                expiry = expiry
            )

            val existing = grouped.getOrPut(key) { Qty() }
            existing.opened += opened
            existing.sealed += sealed
        }

        val hl7 = StringBuilder()

        // Header
        hl7.append("MSH|^~\\&|PILLCOUNTER|STORE|PMS|PHARMACY|")
            .append(now)
            .append("||INR^U05|")
            .append(messageId)
            .append("|P|2.5\n")

        hl7.append("MSA|AA|").append(requestId).append("\n")
        hl7.append("ORC|RE|").append(orderId).append("\n")

        var index = 1

        grouped.forEach { (key, value) ->
            val total = value.opened + value.sealed

            // INV
            hl7.append("INV|")
                .append(index)
                .append("|")
                .append(key.ndc)
                .append("^")
                .append(key.name)
                .append("|||||||||")
                .append(total)
                .append("|||||\n")

            // ZIN
            if (value.opened == 0 && value.sealed == 0) {
                hl7.append("ZIN|")
                    .append(index)
                    .append("|NA|0||\n")
            } else {
                if (value.opened > 0) {
                    hl7.append("ZIN|")
                        .append(index)
                        .append("|OPENED|")
                        .append(value.opened)
                        .append("|")
                        .append(key.lot)
                        .append("|")
                        .append(key.expiry)
                        .append("\n")
                }

                if (value.sealed > 0) {
                    hl7.append("ZIN|")
                        .append(index)
                        .append("|SEALED|")
                        .append(value.sealed)
                        .append("|")
                        .append(key.lot)
                        .append("|")
                        .append(key.expiry)
                        .append("\n")
                }
            }

            index++
        }

        return hl7.toString()
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