package com.rite.pillcounting.feature.hl7.util


import com.rite.pillcounting.core.models.toImageLabel
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
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
        val messageId = txn.hl7MessageControlId?.takeIf { it.isNotBlank() }
            ?: System.currentTimeMillis().toString()

        val txnDetails = txnDetails.filter { !it.isDeleted }
        // Each bottle's true pill count is live-summed here from its own txnDetailsIds against
        // the (already non-deleted-filtered) detail rows — there is no stored pill count on
        // BottleInfo itself, so this keeps a bottle's reported count correct even if a detail
        // row was deleted/redone after the bottle was scanned.
        val pillCountByDetailsId = txnDetails.associate { it.txnDetailsId to (it.pillCount ?: 0) }
        val bottles = BottleInfoJson.decode(txn.bottleInfoListJson)
        val bottleCounts = bottles.map { bottle ->
            bottle.txnDetailsIds.sumOf { id -> pillCountByDetailsId[id] ?: 0 }
        }

        val totalCount = bottleCounts.sum().takeIf { bottles.isNotEmpty() }
            ?: txnDetails.sumOf { it.pillCount ?: 0 }.takeIf { txnDetails.isNotEmpty() }
            ?: txn.targetCount
            ?: 0
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
                orc.orderStatus = null
                orc.orderingProviderId = pharmacistName?.takeIf { it.isNotBlank() }
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
                bottles = bottles,
                bottleCounts = bottleCounts,
                lotNumber = lotNumber,
                expirationDate = expirationDate,
                serialNumber = serialNumber,
                totalCount = totalCount,
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
                    z.rxNumber = txn.hl7SequenceNumber?.takeIf { it.isNotBlank() } ?: orderId
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
        return buildInventoryMessageChunks(batch, txns, approvedBy, config).single().message
    }

    /** One chunk of a (possibly split) inventory sync — see [buildInventoryMessageChunks]. */
    data class InventoryChunk(
        val chunkIndex: Int,
        val totalChunks: Int,
        val itemTotal: Int,
        val message: String
    )

    /**
     * Splits a batch's grouped inventory rows into multiple complete, independently
     * sendable INR^U06 messages so no single message exceeds [maxRowsPerChunk] INV
     * segments — keeping each message safely under PMS's message-size ceiling
     * regardless of how large the overall batch grows.
     *
     * Every chunk carries this batch's id in ORC-2, a stable inventoryGroupId (derived
     * from [BatchEntity.bucketId]) in ORC-3 so PMS updates the same inventory record
     * across repeated syncs instead of creating a new one each time, and the batch's
     * grand total in ZAD-3. A BTS trailer right after MSH marks this message's position
     * (BTS-1 = this chunk's 1-based index, BTS-2 = total chunk count, BTS-3 = this
     * chunk's own item total — all numeric) so PMS can detect a missing/out-of-order
     * chunk. Chunks must be sent in order, each one only after the previous chunk's
     * ACK — see Hl7Repository.
     */
    fun buildInventoryMessageChunks(
        batch: BatchEntity,
        txns: List<BatchTxnDto>,
        approvedBy: String? = null,
        config: HL7Config = HL7Config.current("PILLCOUNTER", "PMS"),
        maxRowsPerChunk: Int = 200
    ): List<InventoryChunk> {

        val now = now()
        val orderId = batch.bucketId.orEmpty()

        // Stable across every inventory sync for this bucket/location — independent of
        // batch.batchId, which changes every time a new local BatchEntity is created (e.g. a
        // retry after app restart, or a fresh count of the same bucket). PMS keys its inventory
        // record off this value (ORC-3) so repeated syncs of the same bucket UPDATE the existing
        // record instead of inserting a duplicate; ORC-2 still carries this specific batch's id
        // for traceability/debugging.
        val inventoryGroupId = "INV-${batch.bucketId.orEmpty().ifEmpty { "DEFAULT" }}"

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

        val rows = grouped.entries.toList()
        val grandTotal = rows.sumOf { it.value.opened + it.value.sealed }
        val chunkedRows = if (rows.isEmpty()) listOf(rows) else rows.chunked(maxRowsPerChunk)
        val totalChunks = chunkedRows.size

        return chunkedRows.mapIndexed { chunkIdx, chunkRows ->
            val chunkIndex = chunkIdx + 1
            val chunkTotal = chunkRows.sumOf { it.value.opened + it.value.sealed }
            val messageId = "RES${System.currentTimeMillis() / 1000}-$chunkIndex"

            val hl7 = HL7(version = config.versionId)
            val builder = hl7.build()

            val message = builder.inuU05 {
                msh { msh ->
                    msh.sendingApplication = config.sendingApplication
                    msh.sendingFacility = config.sendingFacility
                    msh.receivingApplication = config.receivingApplication
                    msh.receivingFacility = ""
                    msh.dateTimeOfMessage = now
                    msh.messageControlId = messageId
                    msh.processingId = "P"
                    msh.versionId = config.versionId
                }

                // BTS is placed right after MSH (chunk-position trailer, describing the
                // whole message that follows) — BTS-1/BTS-2 are numeric per spec:
                // BTS-1 = this chunk's 1-based index, BTS-2 = total chunk count.
                // batchComment carries the shared inventoryGroupId (not free text) so PMS can
                // correlate all chunks/sessions of the same inventory sync.
                bts { bts ->
                    bts.batchMessageCount = chunkIndex.toString()
                    bts.batchComment = totalChunks.toString()
                    bts.batchTotals = chunkTotal.toString()
                }

                equ { equ ->
                    equ.equipmentId = config.sendingApplication
                    equ.eventDateTime = now
                }

                orc { orc ->
                    orc.orderControl = "RE"
                    orc.placerOrderNumber = orderId
                    orc.fillerOrderNumber = inventoryGroupId
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

                chunkRows.forEachIndexed { idx, (key, value) ->
                    val setId = (idx + 1).toString()
                    val total = value.opened + value.sealed

                    inv { inv ->
                        inv.substanceCode = key.ndc
                        inv.substanceCodeSystem = "NDC"
                        inv.substanceName = key.name.ifEmpty { null }
                        inv.inventoryOnHandQuantity = total.toString()
                        inv.lotNumber = key.lot.ifEmpty { null }
                        inv.expirationDate = key.expiry.ifEmpty { null }
                    }

                    // ZIN breaks the INV total down by open/sealed so PMS keeps that
                    // distinction instead of only seeing the combined on-hand count.
                    zin { zin ->
                        zin.setId = "$setId.1"
                        zin.dispenseType = "OPENED"
                        zin.quantity = value.opened.toString()
                        zin.lotNumber = key.lot.ifEmpty { null }
                        zin.expiry = key.expiry.ifEmpty { null }
                    }
                    zin { zin ->
                        zin.setId = "$setId.2"
                        zin.dispenseType = "SEALED"
                        zin.quantity = value.sealed.toString()
                        zin.lotNumber = key.lot.ifEmpty { null }
                        zin.expiry = key.expiry.ifEmpty { null }
                    }
                }

                // ZAD-3 carries the whole batch's grand total on every chunk so PMS
                // can cross-check completeness once all chunks are in.
                zad { zad ->
                    zad.setId = "1"
                    zad.adjustmentType = "CYCLE_COUNT"
                    zad.adjustmentQuantity = grandTotal.toString()
                    zad.adjustmentReason = ZadReasonCode.CYCLE_COUNT
                    zad.adjustmentDateTime = now
                    zad.approvedBy = approvedBy ?: "Unknown"
                }
            }

            InventoryChunk(
                chunkIndex = chunkIndex,
                totalChunks = totalChunks,
                itemTotal = chunkTotal,
                message = message.encode()
            )
        }
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
            val type = detail.type.toImageLabel()
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
                observationValue = "count=0|type=${"SCAN".toImageLabel()}|image=$barcodeFileName",
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

    /**
     * Builds one ZSN row per bottle recorded on the transaction ([BottleInfo] decoded from
     * [PillCountTxnEntity.bottleInfoListJson]). Falls back to a single row built from the
     * caller-supplied [lotNumber]/[expirationDate]/[serialNumber]/[totalCount] when the
     * transaction has no bottle entries (legacy txns predating bottle tracking, or a txn
     * whose pills were all counted before any bottle was ever scanned).
     */
    private fun buildZSN(
        drugCode: String,
        bottles: List<BottleInfo>,
        bottleCounts: List<Int>,
        lotNumber: String?,
        expirationDate: String?,
        serialNumber: String?,
        totalCount: Int,
        now: String
    ): List<ZsnRow> {
        if (bottles.isEmpty()) {
            return listOf(
                ZsnRow(
                    setId = "1",
                    nationalDrugCode = drugCode,
                    lotNumber = lotNumber,
                    expirationDate = expirationDate,
                    packageSerialNumber = serialNumber,
                    quantityFromThisStockItem = totalCount.toString(),
                    captureSource = ScanSource.GS1,
                    captureTimestamp = now,
                    transactionType = ZsnTransactionType.DISPENSE
                )
            )
        }
        return bottles.mapIndexed { index, bottle ->
            ZsnRow(
                setId = (index + 1).toString(),
                nationalDrugCode = drugCode,
                lotNumber = bottle.lotNumber,
                expirationDate = bottle.expirationDate,
                packageSerialNumber = bottle.serialNumber,
                quantityFromThisStockItem = bottleCounts[index].toString(),
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