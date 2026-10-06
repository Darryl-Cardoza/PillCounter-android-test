package com.dispensesure.retail.feature.hl7.util


import android.util.Base64
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.models.isDispensedQuantityStep
import com.dispensesure.retail.core.models.toImageLabel
import com.dispensesure.retail.core.security.ImageCrypto
import com.dispensesure.retail.core.scanning.domain.model.BottleInfo
import com.dispensesure.retail.core.scanning.domain.model.BottleInfoJson
import com.dispensesure.retail.core.room.models.BatchEntity
import com.dispensesure.retail.core.room.models.PillCountTxnDetailsEntity
import com.dispensesure.retail.core.room.models.PillCountTxnEntity
import com.dispensesure.retail.core.room.models.dtos.BatchTxnDto
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
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

    private val logger = AppLogger.create<HL7MessageBuilder>()

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
        pharmacistName: String?,
        // RXD-10 carries the operator as ^Family^Given — no id component. The joined
        // [pharmacistName] is kept
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

        // Only the prescribed-count step is the dispensed quantity. The pour-out, recount,
        // vial and remainder steps measure other things, and summing them all reported
        // several times the pills actually dispensed.
        val dispensedDetails = txnDetails.filter { it.type.isDispensedQuantityStep() }
        // Each bottle's true pill count is live-summed here from its own txnDetailsIds against
        // the detail rows — there is no stored pill count on
        // BottleInfo itself, so this keeps a bottle's reported count correct even if a detail
        // row was deleted/redone after the bottle was scanned.
        val pillCountByDetailsId = dispensedDetails.associate { it.txnDetailsId to (it.pillCount ?: 0) }
        val bottles = BottleInfoJson.decode(txn.bottleInfoListJson)
        val bottleCounts = bottles.map { bottle ->
            bottle.txnDetailsIds.sumOf { id -> pillCountByDetailsId[id] ?: 0 }
        }

        // Must be > 0, not just "bottles exist": a bottle scanned after its pills were counted
        // never gets those rows linked, so its sum is 0 while the step rows are right there.
        val totalCount = bottleCounts.sum().takeIf { it > 0 }
            ?: dispensedDetails.sumOf { it.pillCount ?: 0 }.takeIf { dispensedDetails.isNotEmpty() }
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
                    z.drugImage = buildZuiImagePayload(txnId = txn.txnId, bottles = bottles, details = txnDetails)
                        ?.joinToString("^") { group -> group.joinToString("&") }
                    z.drugLotNumber = effectiveLotNumber
                    z.drugSerialNumber = effectiveSerialNumber
                    z.drugExpirationDate = effectiveExpirationDate
                }
                Hl7Format.EYECON.sendingApplication -> {
                    // EyeCon uses a 25-field fixed ZUI layout that the ZUIDispenseBuilder
                    // (11 fields max) cannot express. Use the builder for ZUI-1 only so the
                    // segment appears in the encoded message, then replace the whole line with
                    // the correct 25-field segment in buildEyeConZuiSegment below.
                    val eyeConNdc = drugCode.replace("-", "")
                    zui { z -> z.ndc = eyeConNdc }
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
                    b.observationValue = obx.observationValue
                    b.observationValue2 = obx.observationValueText
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

        var encoded = message.encode()
        // The library HL7-escapes '^' and '&' inside field strings to '\S\' and '\T\'.
        // ZUI-8 carries multi-group image data joined by '^' (groups) and '&' (parts within a
        // group) — these must be real HL7 separator characters, not escaped literals.
        // Unescape only within the ZUI segment line so other segments aren't affected.
        if (config.messageFormat == Hl7Format.VIVID) {
            encoded = encoded.replace(Regex("(ZUI\\|[^\r\n]*)")) { match ->
                match.value.replace("\\S\\", "^").replace("\\T\\", "&")
            }
        }
        return if (config.messageFormat == Hl7Format.EYECON) {
            val eyeConNdc = drugCode.replace("-", "")
            val firstName = pharmacistGivenName.orEmpty()
            val stockVerification = if (isNdcVerified) "A" else ""
            val zuiSegment = buildEyeConZuiSegment(
                ndc = eyeConNdc,
                drugName = drugName,
                firstName = firstName,
                rxNumber = orderId,
                fillNumber = "1",
                verifiedBy = firstName,
                stockBottleVerification = stockVerification,
                dispensedQuantity = totalCount.toString(),
                fillStatus = "complete",
                stockBottleBarcodeNdc = eyeConNdc,
            )
            // Replace the stub ZUI line the builder emitted with the full 25-field segment.
            encoded.replace(Regex("ZUI\\|[^\r\n]*"), zuiSegment)
        } else {
            encoded
        }
    }

    /**
     * Builds the EyeCon fixed 25-field ZUI segment string.
     * Field positions match the EyeCon PMS C# parser spec:
     * ZUI-1=ndc, ZUI-2=drugName, ZUI-3=firstName, ZUI-4=prescriptionNumber,
     * ZUI-5=fillNumber, ZUI-6=verifiedBy, ZUI-7=stockBottleVerification,
     * ZUI-18=dispensedQuantity, ZUI-19=fillStatus, ZUI-21=stockBottleBarcodeNdc.
     */
    private fun buildEyeConZuiSegment(
        ndc: String,
        drugName: String,
        firstName: String,
        rxNumber: String,
        fillNumber: String,
        verifiedBy: String,
        stockBottleVerification: String,
        dispensedQuantity: String,
        fillStatus: String,
        stockBottleBarcodeNdc: String,
    ): String {
        val fields = Array(26) { "" }
        fields[0] = "ZUI"
        fields[1] = ndc
        fields[2] = drugName
        fields[3] = firstName
        fields[4] = rxNumber
        fields[5] = fillNumber
        fields[6] = verifiedBy
        fields[7] = stockBottleVerification
        fields[18] = dispensedQuantity
        fields[19] = fillStatus
        fields[21] = stockBottleBarcodeNdc
        return fields.joinToString("|")
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
     * on the wire (this is the shared iOS/Android INU^U05 format); [chunkIndex]/[totalChunks]
     * only drive local resume bookkeeping — see [Hl7Repository].
     *
     * Wire shape per chunk (shared iOS/Android INU^U05 format):
     * ```
     * MSH
     * EQU
     * ORC
     * OBX (OPERATOR_NAME, blank sub-id)
     * [ INV (one per ndc/lot/expiry group, Set-ID keyed)
     *   OBX (SEALED_QTY, OBX-5 = `pills^bottles`)
     *   OBX (OPEN_QTY, OBX-5 = `pills^bottles`)
     *   OBX (IMG001..IMGnnn, one per photo, only if the group has photos) ]  repeats per group
     * ZAD (batch note, only if the batch has one — always last segment)
     * ```
     * No NTE, no BTS, no ZIN.
     *
     * Both quantity rows always carry both components, `0^0` included, so PMS parses
     * one fixed shape. Sealed bottles are summed; a line with loose pills counts as
     * one open bottle (no open-bottle column exists in the stock model). The
     * inventory screen derives its bottle count the same way — if an open-bottle
     * column ever lands, this and `InventoryScanViewModel.toRecentRows` change together.
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
        data class Qty(
            var opened: Int = 0,
            var sealed: Int = 0,
            var openedBottles: Int = 0,
            var sealedBottles: Int = 0,
            val imagePaths: MutableList<String> = mutableListOf()
        )

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
            // No open-bottle column exists: a line with loose pills counts as one
            // open bottle, matching what the inventory screen already shows.
            e.sealedBottles += txn.bottleQty ?: 0
            if ((txn.looseQty ?: 0) > 0) e.openedBottles++
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
                    // ORC-2: bucketId^requestId as components (not separate fields).
                    orc.placerOrderNumber = if (!requestId.isNullOrBlank()) "$orderId^$requestId" else orderId
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
                        inv.inventoryOnHandQuantity = total.toString()
                        inv.units = "TAB"
                        inv.expirationDate = key.expiry.ifEmpty { null }
                        inv.lotNumber = key.lot.ifEmpty { null }
                    }

                    obx { obx ->
                        obx.setId = nextObxSetId()
                        obx.valueType = "NM"
                        obx.observationId = "SEALED_QTY"
                        obx.subId = invSetId
                        // Two components, not one string: HL7Escaping would turn a
                        // literal "2000^2" into 2000\S\2.
                        obx.observationValue = value.sealed.toString()
                        obx.observationValue2 = value.sealedBottles.toString()
                        obx.resultStatus = "F"
                    }
                    obx { obx ->
                        obx.setId = nextObxSetId()
                        obx.valueType = "NM"
                        obx.observationId = "OPEN_QTY"
                        obx.subId = invSetId
                        obx.observationValue = value.opened.toString()
                        obx.observationValue2 = value.openedBottles.toString()
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

            // HL7 library escapes literal '^' to '\S\' when it appears inside a field
            // string — but ORC-2.2 (requestId component) must be a real component separator.
            // Post-process the encoded ORC line to restore the component separator.
            val encoded = if (requestId != null) {
                message.encode().replace(
                    "ORC|RE|${orderId}\\S\\${requestId}",
                    "ORC|RE|${orderId}^${requestId}"
                )
            } else {
                message.encode()
            }
            InventoryChunk(
                chunkIndex = chunkIndex,
                totalChunks = totalChunks,
                itemTotal = chunkTotal,
                message = encoded
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
     * each followed by its raw (unannotated) image when present —
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
            .flatMap { listOfNotNull(it.imagePath, it.rawImagePath) }
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
            .onFailure { e -> logger.e("Failed to read/decrypt image for HL7 encoding: $path", e, event = LogEvent.FILE_READ_ERROR) }
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

        // Each detail's raw (unannotated) image gets its own row right after it.
        val detailImages = details.flatMap { detail ->
            val type = detail.type.toImageLabel()
            listOfNotNull(
                type to (detail.imagePath?.let { "images/${File(it).name}" } ?: ""),
                detail.rawImagePath?.let { "${type}_raw" to "images/${File(it).name}" }
            )
        }
        val detailRows = detailImages.mapIndexed { index, (text, value) ->
            ObxRow(
                setId = (index + 1).toString(),
                valueType = "RP",
                observationId = "IMG${(index + 1).toString().padStart(3, '0')}",
                observationText = text,
                observationValue = value,
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
        // No bottle has linked rows, so RXD-4's fallback total carries here too, on the
        // first row — the ZSN quantities still sum to the dispensed total.
        val unlinked = bottleCounts.sum() == 0
        return bottles.mapIndexed { index, bottle ->
            val quantity = if (unlinked && index == 0) totalCount else bottleCounts[index]
            ZsnRow(
                setId = (index + 1).toString(),
                nationalDrugCode = drugCode,
                lotNumber = bottle.lotNumber,
                expirationDate = bottle.expirationDate,
                packageSerialNumber = bottle.serialNumber,
                quantityFromThisStockItem = quantity.toString(),
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
