package com.rite.pillcounting.feature.hl7.util


import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import org.rite.hl7.HL7
import org.rite.hl7.builder.ScanSource
import org.rite.hl7.builder.ZadReasonCode
import org.rite.hl7.builder.ZsnTransactionType
import org.rite.hl7.builder.ZsvMatchStrength
import org.rite.hl7.builder.ZsvValidationResult
import org.rite.hl7.builder.ZuiTransactionStatus

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * =========================================================
 * HL7MessageBuilder (Hl7Core edition)
 * =========================================================
 *
 * Kotlin port of iOS `HL7CompletionBuilder`, using the same `HL7Builder`
 * DSL that Hl7Core exposes on both platforms (it's a KMP library — this
 * is not a re-implementation, it's the same builder called natively).
 *
 * This class:
 * - Does NOT send messages
 * - Does NOT access the database
 * - Does NOT use coroutines
 *
 * HL7 version is sourced from [PreferenceHelper.getHl7Version] at call site;
 * a static convenience default ("2.5") is used when no preference is available.
 */

/**
 * HL7 sending application formats supported when composing outbound messages.
 * Persisted via [PreferenceHelper.saveHl7Format]/[PreferenceHelper.getHl7Format].
 */
enum class Hl7Format(val sendingApplication: String) {
    DISPENSESURE("DISPENSESURE"),
    EYECON("EYECON"),
    VIVID("VIVID");

    companion object {
        val DEFAULT = DISPENSESURE

        fun fromSendingApplication(value: String?): Hl7Format =
            entries.firstOrNull { it.sendingApplication == value } ?: DEFAULT
    }
}

data class HL7Config(
    val sendingApplication: String,
    val sendingFacility: String,
    val receivingApplication: String,
    val receivingFacility: String,
    val versionId: String
) {
    companion object {
        /**
         * Sourced from app settings: the terminal name identifies this
         * station as the sending facility, and the configured PMS host name
         * is used as the receiving facility since the PMS routes by t
         * identity. Mirrors iOS `HL7Config.current`.
         */
        fun current(
            selectedTerminalName: String,
            pmsHostName: String,
            hl7Version: String = PreferenceHelper.DEFAULT_HL7_VERSION,
            hl7Format: Hl7Format = Hl7Format.DEFAULT
        ) = HL7Config(
            sendingApplication = hl7Format.sendingApplication,
            sendingFacility = selectedTerminalName,
            receivingApplication = "PMS",
            receivingFacility = pmsHostName,
            versionId = hl7Version
        )
    }
}

/**
 * Builds wire-encoded HL7 strings for outbound messages.
 *
 * All builder methods are **object-level** (companion object) to keep the
 * call site simple: `HL7MessageBuilder.buildDispenseMessage(...)`.
 *
 * The [HL7] facade is instantiated lazily per-call using the HL7 version
 * embedded in [HL7Config] so that version-sensitive trigger events
 * (e.g. RDS^O13 vs RDS^O01) resolve correctly.
 */
object HL7MessageBuilder {

    // =========================================================
    // DISPENSE (RDS O13)
    // =========================================================

    fun buildDispenseMessage(
        txn: PillCountTxnEntity,
        txnDetails: List<PillCountTxnDetailsEntity>,
        drugCode: String,
        scannedDrugCode: String,
        drugName: String,
        pharmacistId: String?,
        pharmacistName: String?,
        location: String? = null,
        // Optional fields that may not yet exist on every PillCountTxnEntity build;
        // passed explicitly until Room entities are confirmed to carry them.
        lotNumber: String? = null,
        expirationDate: String? = null,
        serialNumber: String? = null,
        isNdcVerified: Boolean = false,
        config: HL7Config = HL7Config.current("PILLCOUNTER", "PMS")
    ): String {

        val hl7 = HL7(version = config.versionId)
        val builder = hl7.build()

        val now = now()
        val messageId = System.currentTimeMillis().toString()

        val txnDetails = txnDetails.filter { !it.isDeleted }
        val details = txnDetails.map { detail ->
            DummyBottleInfo(
                lotNumber = "LOT123456",
                exp =  "20991231",
                serialNumber = "SN123456",
                pillCount = detail.pillCount?.toString()
            )
        }.filter { it.pillCount != null }

        val totalCount = details.sumOf { it.pillCount?.toIntOrNull() ?: 0 }
        val orderId = txn.rxNo ?: txn.txnId.toString()


        val message = builder.rdsO13 {
            msh { msh ->
                msh.sendingApplication = config.sendingApplication
                msh.sendingFacility = config.sendingFacility
                msh.receivingApplication = config.receivingApplication
                msh.receivingFacility = config.receivingFacility
                msh.dateTimeOfMessage = now
                msh.messageControlId = messageId
                msh.processingId = "P"
                msh.versionId = config.versionId
            }

            orc { orc ->
                orc.orderControl = "RE"
                orc.placerOrderNumber = orderId
                orc.orderStatus = "CM"
            }

            pid { pid ->
                pid.patientId = orderId
            }

            rxd { rxd ->
                rxd.dispenseGiveCode = drugCode
                rxd.dispenseGiveName = drugName
                rxd.dispenseGiveCodeSystem = "NDC"
                rxd.dateTimeDispensed = now
                rxd.actualDispenseAmount = totalCount.toString()
                rxd.actualDispenseUnits = "TAB"
                rxd.prescriptionNumber = orderId
                rxd.lotNumber = lotNumber
                rxd.expirationDate = expirationDate
                rxd.dispensingProviderId = pharmacistId
                rxd.dispenseSubIdCounter = "1"
            }

            buildCommonNotes(txnId = txn.txnId.toString(), note = txn.note, totalCount = totalCount)
                .forEach { note ->
                    nte { nte ->
                        nte.setId = note.setId
                        nte.sourceOfComment = note.sourceOfComment
                        nte.comment = note.comment
                        nte.commentType = note.commentType
                    }
                }

            buildImageOBX(
                barcodeImage = txn.barcodeImage,
                details = txnDetails,
                observationId = "DISP_IMG",
                label = "Dispense Image"
            ).forEach { obx ->
                obx { b ->
                    b.setId = obx.setId
                    b.valueType = obx.valueType
                    b.observationId = obx.observationId
                    b.observationText = obx.observationText
                    b.observationValue = obx.observationValue
                    b.resultStatus = obx.resultStatus
                    b.units = obx.units
                }
            }

            buildZSN(
                drugCode = drugCode,
                lotNumber = lotNumber,
                expirationDate = expirationDate,
                serialNumber = serialNumber,
                now = now
            ).forEach { zsn ->
                zsn { z ->
                    z.setId = zsn.setId
                    z.nationalDrugCode = zsn.nationalDrugCode
                    z.lotNumber = zsn.lotNumber
                    z.expirationDate = zsn.expirationDate
                    z.packageSerialNumber = zsn.packageSerialNumber
                    z.quantityFromThisStockItem = zsn.quantityFromThisStockItem
                    z.captureSource = zsn.captureSource
                    z.captureTimestamp = zsn.captureTimestamp
                    z.transactionType = zsn.transactionType
                }
            }

            val isMatch = (drugCode == scannedDrugCode) && (txn.isNdcVerified == true) && !txn.isSubstitute
            val zsv = buildZSV(
                requestedNdc = drugCode,
                scannedNdc = scannedDrugCode,
                isMatch = isMatch,
                now = now
            )
            zsv { z ->
                z.setId = zsv.setId
                // ZSVBuilder field order: setId|dispensedNdc|scannedNdc|validationResult|
                //                         scanSource|validator|validationTimestamp|matchStrength
                z.dispensedNdc = zsv.dispensedNdc
                z.scannedNdc = zsv.scannedNdc
                z.validationResult = zsv.validationResult
                z.scanSource = zsv.scanSource
                z.validator = zsv.validator
                z.validationTimestamp = zsv.validationTimestamp
                z.matchStrength = zsv.matchStrength
            }

            when (config.sendingApplication) {
                Hl7Format.VIVID.sendingApplication -> zui { z ->
                    z.ndc = drugCode
                    z.vividUserName = pharmacistName
                    z.transactionOrderId = orderId
                    z.rxNumber = orderId
                    z.dispensedQuantity = totalCount.toString()
                    z.transactionStatus = ZuiTransactionStatus.DONE
                    z.drugLotNumber = lotNumber
                    z.drugSerialNumber = serialNumber
                    z.drugExpirationDate = expirationDate
                }
                Hl7Format.EYECON.sendingApplication -> zni { z ->
                    z.ndc = drugCode
                    z.drugName = drugName
                    z.userName = pharmacistName
                    z.fillerOrderNumber = orderId
                    z.dispenseAmount = totalCount.toString()
                    z.prescriptionNumber = orderId
                    z.resultStatus = "F"
                }
            }
        }

        return message.encode()
    }

    // =========================================================
    // INVENTORY RESPONSE (INR U06, INV + ZAD)
    // =========================================================

    fun buildInventoryMessage(
        batch: BatchEntity,
        txns: List<BatchTxnDto>,
        approvedBy: String? = null,
        config: HL7Config = HL7Config.current("PILLCOUNTER", "PMS")
    ): String {

        val hl7 = HL7(version = config.versionId)
        val builder = hl7.build()

        val now = now()
        val messageId = "RES${System.currentTimeMillis() / 1000}"
        val orderId = batch.bucketId.orEmpty()
        // Kept for parity with iOS: the ACK for the originating request
        // (MSA-2 = requestId) is a *separate* message, built from the
        // parsed inbound request via HL7.ack(...), not here.
        val requestId = batch.requestIdFromPMS
            ?.takeIf { it.isNotBlank() }
            ?: "REQ${batch.batchId}"

        data class Key(val ndc: String, val name: String, val lot: String, val expiry: String)
        data class Qty(var opened: Int = 0, var sealed: Int = 0)

        val grouped = linkedMapOf<Key, Qty>()
        txns.forEach { txn ->
            val key = Key(
                ndc = txn.ndc.orEmpty(),
                name = txn.drugName.orEmpty(),
                lot = txn.lotNo.orEmpty(),
                expiry = txn.expiry.orEmpty()
            )
            val packageQty = txn.packageQty ?: 0
            val e = grouped.getOrPut(key) { Qty() }
            e.opened += txn.looseQty ?: 0
            e.sealed += (txn.bottleQty ?: 0) * packageQty
        }

        val grandTotal = grouped.values.sumOf { it.opened + it.sealed }

        val message = builder.inrU06 {
            msh { msh ->
                msh.sendingApplication = config.sendingApplication
                msh.sendingFacility = config.sendingFacility
                msh.receivingApplication = config.receivingApplication
                msh.receivingFacility = config.receivingFacility
                msh.dateTimeOfMessage = now
                msh.messageControlId = messageId
                msh.processingId = "P"
                msh.versionId = config.versionId
            }

            orc { orc ->
                orc.orderControl = "RE"
                orc.placerOrderNumber = orderId
            }

            buildCommonNotes(
                txnId = batch.batchId.toString(),
                note = batch.note,
                totalCount = grandTotal,
                isBatch = true
            ).forEach { note ->
                nte { nte ->
                    nte.setId = note.setId
                    nte.sourceOfComment = note.sourceOfComment
                    nte.comment = note.comment
                    nte.commentType = note.commentType
                }
            }

            // INV repeats per drug — unchanged
            grouped.entries.forEachIndexed { idx, (key, value) ->
                val setId = (idx + 1).toString()
                val total = value.opened + value.sealed

                inv { inv ->
                    inv.setId = setId
                    inv.substanceCode = key.ndc
                    inv.substanceCodeSystem = "NDC"
                    inv.substanceName = key.name.ifEmpty { null }
                    inv.inventoryOnHandQuantity = total.toString()
                    inv.lotNumber = key.lot.ifEmpty { null }
                    inv.expirationDate = key.expiry.ifEmpty { null }
                }
            }

            // ZAD is a single segment for the whole message
            zad { zad ->
                zad.setId = "1"
                zad.adjustmentType = "CYCLE_COUNT"
                zad.adjustmentQuantity = grandTotal.toString()
                zad.adjustmentReason = ZadReasonCode.CYCLE_COUNT
                zad.adjustmentDateTime = now
                zad.approvedBy = approvedBy ?: "Unknown"
            }
        }
        return message.encode()
    }

    // =========================================================
    // Private helpers
    // =========================================================

    private data class ObxRow(
        val setId: String,
        val valueType: String,
        val observationId: String,
        val observationText: String?,
        val observationValue: String,
        val resultStatus: String,
        val units: String?
    )

    private fun buildImageOBX(
        barcodeImage: String?,
        details: List<PillCountTxnDetailsEntity>,
        observationId: String,
        label: String
    ): List<ObxRow> {

        val detailRows = details.mapIndexed { index, detail ->
            val count = detail.pillCount ?: 0
            val type = detail.type ?: "UNKNOWN"
            val fileName = detail.imagePath?.let { File(it).name } ?: ""

            ObxRow(
                setId = (index + 1).toString(),
                valueType = "ST",
                observationId = observationId,
                observationText = "$label ${index + 1}",
                observationValue = "count=$count|type=$type|image=$fileName",
                resultStatus = "F",
                units = null
            )
        }

        val barcodeFileName = barcodeImage?.let { File(it).name }.orEmpty()

        val barcodeRow = if (barcodeFileName.isNotEmpty()) {
            ObxRow(
                setId = (detailRows.size + 1).toString(),
                valueType = "ST",
                observationId = observationId,
                observationText = "Barcode Image",
                observationValue = "count=0|type=SCAN|image=$barcodeFileName",
                resultStatus = "F",
                units = null
            )
        } else null

        return if (barcodeRow != null) detailRows + barcodeRow else detailRows
    }

    private data class ZsnRow(
        val setId: String,
        val nationalDrugCode: String?,
        val lotNumber: String?,
        val expirationDate: String?,
        val packageSerialNumber: String?,
        val quantityFromThisStockItem: String,
        val captureSource: String,
        val captureTimestamp: String,
        val transactionType: String
    )

    private data class DummyBottleInfo(
        val lotNumber: String?,
        val exp: String,
        val serialNumber: String?,
        val pillCount: String?


    )

    private fun buildZSN(
        drugCode: String,
        lotNumber: String?,
        expirationDate: String?,
        serialNumber: String?,
        now: String
    ): List<ZsnRow> {
        // Fixed sample data — always exactly 2 ZSN segments, independent of txn details
        val sampleRows = listOf(
            "30" to (lotNumber ?: "LOT123456"),
            "60" to (lotNumber ?: "LOT123457")
        )

        return sampleRows.mapIndexed { index, (qty, lot) ->
            ZsnRow(
                setId = (index + 1).toString(),
                nationalDrugCode = drugCode,
                lotNumber = lot,
                expirationDate = expirationDate ?: "20991231",
                packageSerialNumber = serialNumber ?: "SN12345${index + 6}",
                quantityFromThisStockItem = qty,
                captureSource = ScanSource.GS1,
                captureTimestamp = now,
                transactionType = ZsnTransactionType.DISPENSE
            )
        }
    }

    private data class ZsvRow(
        val setId: String,
        val scannedNdc: String?,
        val dispensedNdc: String?,
        val matchStrength: String?,
        val validationResult: String,
        val validator: String,
        val validationTimestamp: String,
        val scanSource: String
    )

    private fun buildZSV(
        requestedNdc: String,
        scannedNdc: String,
        isMatch: Boolean,
        now: String
    ): ZsvRow = ZsvRow(
        setId = "1",
        scannedNdc = scannedNdc,
        dispensedNdc = requestedNdc,
        matchStrength = if (isMatch) ZsvMatchStrength.EXACT else "MISMATCH",
        validationResult = if (isMatch) ZsvValidationResult.MATCH else ZsvValidationResult.SUBSTITUTION,
        validator = "PillCounter",
        validationTimestamp = now,
        scanSource = ScanSource.UNKNOWN
    )

    private data class NoteRow(
        val setId: String,
        val sourceOfComment: String,
        val comment: String,
        val commentType: String
    )

    private fun buildCommonNotes(
        txnId: String,
        note: String?,
        totalCount: Int,
        isBatch: Boolean = false
    ): List<NoteRow> {
        val label = if (isBatch) "Batch Id" else "Transaction Id"
        var comment = "$label: $txnId | Status: Completed | Total Count: $totalCount"

        note?.trim()?.takeIf { it.isNotEmpty() }?.let {
            comment += " | Note: $it"
        }

        return listOf(
            NoteRow(
                setId = "1",
                sourceOfComment = "L",
                comment = comment,
                commentType = "INFO"
            )
        )
    }

    private fun now(): String =
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
}