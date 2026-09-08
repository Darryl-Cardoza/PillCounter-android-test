package com.rite.pillcounting.feature.hl7.util


import android.util.Base64
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.models.toImageLabel
import com.rite.pillcounting.core.security.ImageCrypto
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import org.rite.hl7.HL7
import org.rite.hl7.builder.ScanSource
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
    val versionId: String,
    /** Which body segments (ZUI/ZNI/etc) to emit — independent of [sendingApplication], which is always this app's name. */
    val messageFormat: Hl7Format = Hl7Format.DEFAULT
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
            hl7Format: Hl7Format = Hl7Format.DEFAULT,
            sendingApplicationName: String = Hl7Format.DISPENSESURE.sendingApplication
        ) = HL7Config(
            sendingApplication = sendingApplicationName,
            sendingFacility = selectedTerminalName,
            receivingApplication = "client",
            receivingFacility = pmsHostName,
            versionId = hl7Version,
            messageFormat = hl7Format
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

    // Resend attempts (PMS reject, ACK timeout, reconnect) call buildDispenseMessage again
    // for the same txn, which used to re-read, decrypt, and re-base64-encode every ZUI-8
    // tray image from disk on each call — multi-MB per image, repeated on every retry.
    // Keyed by txnId (then by path+lastModified, so an edited/rescanned image still misses
    // and re-encodes) instead of a fixed-size LRU: a synced txn is never resent again, so
    // Hl7Repository calls evictImageCache(txnId) once it's synced — memory only ever holds
    // images for txns that are still actually pending, not a rolling cap.
    private val imageEncodeCache = mutableMapOf<Long, MutableMap<Pair<String, Long>, String>>()

    /** Drops cached ZUI-8 image encodings for [txnId] — call once the txn is confirmed synced. */
    fun evictImageCache(txnId: Long) {
        synchronized(imageEncodeCache) { imageEncodeCache.remove(txnId) }
    }

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
        // RXD-10 carries the operator as ID^Family^Given. The joined [pharmacistName] is kept
        // for the segments that want one display string (ORC-12, Z-segments); these two feed the
        // structured components a receiver needs to render an operator name.
        pharmacistFamilyName: String? = null,
        pharmacistGivenName: String? = null,
        location: String? = null,
        // Optional fields that may not yet exist on every PillCountTxnEntity build;
        // passed explicitly until Room entities are confirmed to carry them.
        lotNumber: String? = null,
        expirationDate: String? = null,
        serialNumber: String? = null,
        isNdcVerified: Boolean = false,
        isControlledSubstance: Boolean = false,
        isHazardousDrug: Boolean = false,
        config: HL7Config = HL7Config.current("PILLCOUNTER", "PMS")
    ): String {

        val hl7 = HL7(version = config.versionId)
        val builder = hl7.build()

        val now = now()
        // Falls back to the txnId (not a timestamp) so the outbound MSH-10 is deterministic
        // per transaction — the ACK handler correlates back to this exact txn via
        // PillCountTxnDao.getByMessageControlId, which only works if resends of the same
        // txn always carry the same control id.
        val messageId = txn.hl7MessageControlId?.takeIf { it.isNotBlank() }
            ?: txn.txnId.toString()

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
        val vividOrderId = txn.refillNo?.takeIf { it.isNotBlank() }?.let { "$orderId-$it" } ?: orderId

        // Vivid/EyeCon ZUI/ZSN lot/expiry/serial fall back to the first scanned bottle's
        // data when the caller doesn't supply explicit override values.
        val firstBottle = bottles.firstOrNull()
        val effectiveLotNumber = lotNumber ?: firstBottle?.lotNumber
        val effectiveExpirationDate = expirationDate ?: firstBottle?.expirationDate
        val effectiveSerialNumber = serialNumber ?: firstBottle?.serialNumber


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

            when (config.messageFormat.sendingApplication) {
                // Field mapping verified against Vivid's response-parse spec (ZUI-1 drugId,
                // ZUI-2 VerifiedBy truncated to 10 chars, ZUI-4 RxNo-RefillNo composite,
                // ZUI-6 qtyDispensed, ZUI-7 fill status, ZUI-9/10/11 lot/serial/expiry).
                // ZUI-8 (Base64 tray image log) populated below via drugImages.
                Hl7Format.VIVID.sendingApplication -> zui { z ->
                    z.ndc = drugCode.replace("-", "")
                    z.vividUserName = pharmacistGivenName.orEmpty().take(10)
                    z.transactionOrderId = orderId
                    z.rxNumber = vividOrderId
                    z.dispensedQuantity = totalCount.toString()
                    z.transactionStatus = ZuiTransactionStatus.DONE
                    z.drugImages = buildZuiImagePayload(txnId = txn.txnId, bottles = bottles, details = txnDetails)
                    z.drugLotNumber = effectiveLotNumber
                    z.drugSerialNumber = effectiveSerialNumber
                    z.drugExpirationDate = effectiveExpirationDate
                }
                Hl7Format.EYECON.sendingApplication -> {
                    // EyeCon ZUI — 25-field raw layout; fields 6/18/19/21 are what PMS's
                    // C# parser reads, rest are context fields per EyeCon spec. ZUI-11
                    // (transactionOrderId) is the RxNo-RefillNo composite, same as vividOrderId.
                    val eyeConNdc = drugCode.replace("-", "")
                    val eyeConUserFirstName = pharmacistGivenName.orEmpty()
                    val verifiedBy = eyeConUserFirstName.take(10)
                    zuiEyeCon { z ->
                        z.ndc = eyeConNdc
                        z.drugName = drugName
                        z.userName = eyeConUserFirstName
                        z.prescriptionNumber = orderId
                        z.fillNumber = "1"
                        z.verifiedBy = verifiedBy
                        z.stockBottleVerification = if (isNdcVerified) "A" else "N"
                        z.packetVersion = "1"
                        z.techName = eyeConUserFirstName
                        z.transactionOrderId = vividOrderId
                        z.dispensedQuantity = totalCount.toString()
                        z.fillStatus = "complete"
                        z.stockBottleBarcodeNdc = eyeConNdc
                    }
                }
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
                rxd.dispensingProviderFamilyName = pharmacistFamilyName
                rxd.dispensingProviderGivenName = pharmacistGivenName
                rxd.dispenseSubIdCounter = "1"
            }

            buildCommonNotes(
                txnId = txn.txnId.toString(),
                note = txn.note,
                totalCount = totalCount,
                expectedCount = txn.targetCount
            ).forEach { note ->
                    nte { nte ->
                        nte.setId = note.setId
                        nte.sourceOfComment = note.sourceOfComment
                        nte.comment = note.comment
                        nte.commentType = note.commentType
                    }
                }

            val imageObxRows = buildImageOBX(
                bottles = bottles,
                details = txnDetails
            )
            val controlledHazardousObxRows = buildControlledHazardousOBX(
                setIdStart = imageObxRows.size + 1,
                isControlledSubstance = isControlledSubstance,
                isHazardousDrug = isHazardousDrug
            )
            (imageObxRows + controlledHazardousObxRows).forEach { obx ->
                obx { b ->
                    b.setId = obx.setId
                    b.valueType = obx.valueType
                    b.observationId = obx.observationId
                    b.observationText = obx.observationText
                    b.observationIdCodingSystem = obx.observationIdCodingSystem
                    b.observationValue = obx.observationValue
                    b.observationValueText = obx.observationValueText
                    b.observationValueCodingSystem = obx.observationValueCodingSystem
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
        }

        return message.encode()
    }

    // =========================================================
    // INVENTORY RESPONSE (INU^U05, INV + ZAD)
    // =========================================================

    fun buildInventoryMessage(
        batch: BatchEntity,
        txns: List<BatchTxnDto>,
        config: HL7Config = HL7Config.current("PILLCOUNTER", "PMS")
    ): String {
        return buildInventoryMessageChunks(batch, txns, config).single().message
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
     * sendable INU^U05 messages so no single message exceeds [maxRowsPerChunk] INV
     * groups — keeping each message safely under PMS's message-size ceiling
     * regardless of how large the overall batch grows. Chunk position is not encoded
     * on the wire (matches the iOS-produced format exactly); [chunkIndex]/[totalChunks]
     * only drive local resume bookkeeping — see [Hl7Repository].
     *
     * Wire shape per chunk (matches iOS's current INU^U05 output):
     * ```
     * MSH
     * EQU
     * ORC
     * OBX (OPERATOR_NAME, blank sub-id)
     * [ INV (one per ndc/lot/expiry group, Set-ID keyed)
     *   OBX (SEALED_QTY)
     *   OBX (OPEN_QTY)
     *   OBX (IMG001..IMGnnn, one per photo, only if the group has photos) ]  repeats per group
     * ZAD (batch note, only if the batch has one — always last segment)
     * ```
     * No NTE, no BTS, no ZIN.
     *
     * ORC-2 carries this batch's bucketId, and — when this batch answers a
     * PMS-originated INR^U06 request (`batch.requestIdFromPMS` non-blank) — that
     * request id appended as `<bucketId>^<requestIdFromPMS>` so PMS can correlate
     * this response with its original request. Chunks must be sent in order, each
     * one only after the previous chunk's ACK — see Hl7Repository.
     */
    fun buildInventoryMessageChunks(
        batch: BatchEntity,
        txns: List<BatchTxnDto>,
        config: HL7Config = HL7Config.current("PILLCOUNTER", "PMS"),
        maxRowsPerChunk: Int = 200
    ): List<InventoryChunk> {

        val now = now()
        val orderId = batch.bucketId.orEmpty()
        val requestId = batch.requestIdFromPMS?.trim()?.ifEmpty { null }

        data class Key(val ndc: String, val name: String, val lot: String, val expiry: String)
        data class Qty(var opened: Int = 0, var sealed: Int = 0, val imagePaths: MutableList<String> = mutableListOf())

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
            txn.imagePaths?.let { e.imagePaths.addAll(it) }
        }

        // Zero-qty rows are still sent: omitting a counted-zero row would make it
        // indistinguishable from a drug PMS never asked about.
        val rows = grouped.entries.toList()
        val chunkedRows = if (rows.isEmpty()) listOf(rows) else rows.chunked(maxRowsPerChunk)
        val totalChunks = chunkedRows.size

        return chunkedRows.mapIndexed { chunkIdx, chunkRows ->
            val chunkIndex = chunkIdx + 1
            val chunkTotal = chunkRows.sumOf { it.value.opened + it.value.sealed }
            val messageId = "RES${System.currentTimeMillis() / 1000}-$chunkIndex"

            val hl7 = HL7(version = config.versionId)
            val builder = hl7.build()

            var obxSetId = 0
            fun nextObxSetId() = (++obxSetId).toString()

            var imgSeq = 0
            fun nextImgObservationId() = "IMG${(++imgSeq).toString().padStart(3, '0')}"

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

                equ { equ ->
                    equ.equipmentId = config.sendingApplication
                    equ.eventDateTime = now
                }

                orc { orc ->
                    orc.orderControl = "RE"
                    orc.placerOrderNumber = orderId
                    orc.placerOrderCorrelationId = requestId
                }

                obx { obx ->
                    obx.setId = nextObxSetId()
                    obx.valueType = "ST"
                    obx.observationId = "OPERATOR_NAME"
                    obx.observationValue = batch.userName.orEmpty()
                    obx.resultStatus = "F"
                }

                chunkRows.forEachIndexed { idx, (key, value) ->
                    val invSetId = (idx + 1).toString()
                    val total = value.opened + value.sealed

                    inv { inv ->
                        inv.setId = invSetId
                        inv.substanceCode = key.ndc
                        inv.substanceName = key.name.ifEmpty { null }
                        inv.substanceCodeSystem = "L"
                        inv.statusCode = "A"
                        inv.statusText = "Active"
                        inv.statusCodeSystem = "HL70383"
                        inv.itemTypeCode = "DRUG"
                        inv.itemTypeText = "Drug"
                        inv.itemTypeCodeSystem = "HL70384"
                        inv.quantityOnHand = total.toString()
                        inv.inventoryOnHandQuantity = total.toString()
                        // INV-9 left unset: no PMS-expected-qty data flows into BatchTxnDto yet,
                        // so setting it to the counted total would fake discrepancy as always 0.
                        inv.unitsCode = "TAB"
                        inv.unitsText = "Tablets"
                        inv.unitsCodeSystem = "UCUM"
                        inv.expirationDate = key.expiry.ifEmpty { null }
                        inv.lotNumber = key.lot.ifEmpty { null }
                    }

                    obx { obx ->
                        obx.setId = nextObxSetId()
                        obx.valueType = "NM"
                        obx.observationId = "SEALED_QTY"
                        obx.subId = invSetId
                        obx.observationValue = value.sealed.toString()
                        obx.resultStatus = "F"
                    }
                    obx { obx ->
                        obx.setId = nextObxSetId()
                        obx.valueType = "NM"
                        obx.observationId = "OPEN_QTY"
                        obx.subId = invSetId
                        obx.observationValue = value.opened.toString()
                        obx.resultStatus = "F"
                    }
                    value.imagePaths.forEach { path ->
                        obx { obx ->
                            obx.setId = nextObxSetId()
                            obx.valueType = "RP"
                            obx.observationId = nextImgObservationId()
                            obx.subId = invSetId
                            obx.observationValue = "images/${File(path).name}"
                            obx.resultStatus = "F"
                        }
                    }
                }

                // ZAD carries the batch note (if any) and is always the last segment
                // in the message — no separate NTE for the note (matches iOS's INU^U05 shape).
                batch.note?.trim()?.takeIf { it.isNotEmpty() }?.let { note ->
                    zad { z ->
                        z.setId = "1"
                        z.approvedBy = batch.userName.orEmpty()
                        z.comment = note
                    }
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
        val units: String?,
        val observationIdCodingSystem: String? = null,
        val observationValueText: String? = null,
        val observationValueCodingSystem: String? = null
    )

    /**
     * Builds ZUI-8's tray-photo payload: one [batchInfo, countInfo, base64Data] group
     * per image (`&`-joined on the wire, images `^`-joined). Sourced from the barcode
     * image plus the target-verification (dispense count) and vial-step images —
     * not the full detail set (no before/after stock-bottle or recount images).
     *
     * Batch/count numbering: the app has no batching (multi-tray split) or
     * double-count (recount) tracking yet — every image is reported as
     * "batch 1 of 1", count 1 of 1. Revisit once those features add real fields.
     */
    private fun buildZuiImagePayload(
        txnId: Long,
        bottles: List<BottleInfo>,
        details: List<PillCountTxnDetailsEntity>
    ): List<List<String>>? {
        val stepImages = details
            .filter { it.type == StepState.TARGET_VERIFICATION.name || it.type == StepState.VIAL.name }
            .mapNotNull { it.imagePath }
        val barcodeImages = bottles.mapNotNull { it.barcodeImagePath }
        val imagePaths = stepImages + barcodeImages
        if (imagePaths.isEmpty()) return null

        val countTotal = imagePaths.size
        return imagePaths.mapIndexedNotNull { index, path ->
            val base64 = encodeImageCached(txnId, path) ?: return@mapIndexedNotNull null
            val countIndex = index + 1
            // "1B1" = batch 1 of 1 (no batching feature yet); "${countIndex}C$countTotal" =
            // this image's count-attempt index of the total image count, per Vivid spec format.
            listOf("1B1", "${countIndex}C$countTotal", base64)
        }.takeIf { it.isNotEmpty() }
    }

    /** Reads, decrypts, and base64-encodes an image file, cached per-txn by (path, lastModified) so repeat resends of the same unchanged file skip the disk read and re-encode. */
    private fun encodeImageCached(txnId: Long, path: String): String? {
        val file = File(path)
        val lastModified = file.lastModified()
        if (lastModified == 0L) return null // file doesn't exist / unreadable — don't cache
        val key = path to lastModified
        synchronized(imageEncodeCache) { imageEncodeCache[txnId]?.get(key) }?.let { return it }

        val encoded = runCatching { file.readBytes() }
            .mapCatching { bytes -> if (ImageCrypto.isEncrypted(bytes)) ImageCrypto.decrypt(bytes) else bytes }
            .getOrNull()
            ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            ?: return null

        synchronized(imageEncodeCache) {
            imageEncodeCache.getOrPut(txnId) { mutableMapOf() }[key] = encoded
        }
        return encoded
    }

    private fun buildImageOBX(
        bottles: List<BottleInfo>,
        details: List<PillCountTxnDetailsEntity>
    ): List<ObxRow> {

        val detailRows = details.mapIndexed { index, detail ->
            val type = detail.type.toImageLabel()
            val imagePath = detail.imagePath?.let { "images/${File(it).name}" } ?: ""

            ObxRow(
                setId = (index + 1).toString(),
                valueType = "RP",
                observationId = "IMG${(index + 1).toString().padStart(3, '0')}",
                observationText = type,
                observationValue = imagePath,
                resultStatus = "F",
                units = null
            )
        }

        val barcodeRows = bottles.mapIndexedNotNull { index, bottle ->
            val barcodeFileName = bottle.barcodeImagePath?.let { File(it).name }.orEmpty()
            if (barcodeFileName.isEmpty()) return@mapIndexedNotNull null

            val setId = detailRows.size + index + 1
            ObxRow(
                setId = setId.toString(),
                valueType = "RP",
                observationId = "IMG${setId.toString().padStart(3, '0')}",
                observationText = "Barcode Image ${index + 1}",
                observationValue = "images/$barcodeFileName",
                resultStatus = "F",
                units = null
            )
        }

        return detailRows + barcodeRows
    }

    /**
     * OBX-3 = CONTROLLED_SUBSTANCE/HAZARDOUS_DRUG identifier, OBX-5 = Y/N per HL70136,
     * e.g. `OBX|4|CE|CONTROLLED_SUBSTANCE^Controlled Substance^L||Y^Yes^HL70136`.
     */
    private fun buildControlledHazardousOBX(
        setIdStart: Int,
        isControlledSubstance: Boolean,
        isHazardousDrug: Boolean
    ): List<ObxRow> = listOf(
        ObxRow(
            setId = setIdStart.toString(),
            valueType = "CE",
            observationId = "CONTROLLED_SUBSTANCE",
            observationText = "Controlled Substance",
            observationValue = if (isControlledSubstance) "Y" else "N",
            observationValueText = if (isControlledSubstance) "Yes" else "No",
            observationValueCodingSystem = "HL70136",
            resultStatus = "F",
            units = null,
            observationIdCodingSystem = "L"
        ),
        ObxRow(
            setId = (setIdStart + 1).toString(),
            valueType = "CE",
            observationId = "HAZARDOUS_DRUG",
            observationText = "Hazardous Drug",
            observationValue = if (isHazardousDrug) "Y" else "N",
            observationValueText = if (isHazardousDrug) "Yes" else "No",
            observationValueCodingSystem = "HL70136",
            resultStatus = "F",
            units = null,
            observationIdCodingSystem = "L"
        )
    )

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
        isBatch: Boolean = false,
        expectedCount: Int? = null
    ): List<NoteRow> {
        val label = if (isBatch) "Batch Id" else "Transaction Id"
        var comment = "$label: $txnId | Status: Completed | Total Count: $totalCount"

        if (expectedCount != null && expectedCount != totalCount) {
            val diff = totalCount - expectedCount
            comment += " | Count Mismatch: Expected $expectedCount, Counted $totalCount, Diff $diff"
        }

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