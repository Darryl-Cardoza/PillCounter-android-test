package com.dispensesure.retail.feature.hl7.util


import android.util.Base64
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.models.isDispensedQuantityStep
import com.dispensesure.retail.core.models.toImageLabel
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottleJson
import com.dispensesure.retail.core.security.ImageCrypto
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import org.rite.hl7.HL7
import org.rite.hl7.builder.ScanSource
import org.rite.hl7.builder.ZsnTransactionType
import org.rite.hl7.builder.ZsvMatchStrength
import org.rite.hl7.builder.ZsvValidationResult
import org.rite.hl7.builder.ZuiTransactionStatus
import org.rite.hl7.model.segment.ZDSSegment
import java.io.File
import org.rite.hl7.config.HL7Config as LibHl7Config
import org.rite.hl7.config.HL7Version as LibHl7Version

/**
 * =========================================================
 * DispenseMessageBuilder (Hl7Core edition)
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
 * a static convenience default ("2.3.1") is used when no preference is available.
 */

/**
 * Builds wire-encoded HL7 strings for outbound messages.
 *
 * All builder methods are **object-level** (companion object) to keep the
 * call site simple: `DispenseMessageBuilder.buildDispenseMessage(...)`.
 *
 * The [HL7] facade is instantiated lazily per-call using the HL7 version
 * embedded in [HL7Config] so that version-sensitive trigger events
 * (e.g. RDS^O13 vs RDS^O01) resolve correctly.
 */
object DispenseMessageBuilder {

    private val logger = AppLogger.create<DispenseMessageBuilder>()

    // Resend attempts (PMS reject, ACK timeout, reconnect) call buildDispenseMessage again
    // for the same txn, which used to re-read, decrypt, and re-base64-encode every ZUI-8
    // tray image from disk on each call — multi-MB per image, repeated on every retry.
    // Keyed by txnId (then by path+lastModified, so an edited/rescanned image still misses
    // and re-encodes) instead of a fixed-size LRU: a synced txn is never resent again, so
    // DispenseHl7Repository calls evictImageCache(txnId) once it's synced — memory only ever holds
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
        txn: DispenseTxnEntity,
        txnDetails: List<DispenseCountEntity>,
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
        // Optional fields that may not yet exist on every DispenseTxnEntity build;
        // passed explicitly until Room entities are confirmed to carry them.
        lotNumber: String? = null,
        expirationDate: String? = null,
        serialNumber: String? = null,
        isNdcVerified: Boolean = false,
        isControlledSubstance: Boolean = false,
        isHazardousDrug: Boolean = false,
        /** ZDS-08: "NDC" in the US, "DIN" in Canada. */
        drugCodeLabel: String = "NDC",
        config: HL7Config = HL7Config.current("PILLCOUNTER", "PMS")
    ): String {

        val hl7 = HL7(LibHl7Config(LibHl7Version.from(config.versionId)))
        val builder = hl7.build()

        val now = hl7TimestampNow()
        // Falls back to the txnId (not a timestamp) so the outbound MSH-10 is deterministic
        // per transaction — the ACK handler correlates back to this exact txn via
        // DispenseTxnDao.getByMessageControlId, which only works if resends of the same
        // txn always carry the same control id.
        val messageId = txn.hl7MessageControlId?.takeIf { it.isNotBlank() }
            ?: txn.txnId.toString()

        // Only the prescribed-count step is the dispensed quantity. The pour-out, recount,
        // vial and remainder steps measure other things, and summing them all reported
        // several times the pills actually dispensed.
        val dispensedDetails = txnDetails.filter { it.step.isDispensedQuantityStep() }
        // Each bottle's true pill count is live-summed here from its own countIds against
        // the detail rows — there is no stored pill count on
        // DispenseBottle itself, so this keeps a bottle's reported count correct even if a detail
        // row was deleted/redone after the bottle was scanned.
        val pillCountByCountId = dispensedDetails.associate { it.countId to (it.pillCount ?: 0) }
        val bottles = DispenseBottleJson.decode(txn.bottlesJson)
        val bottleCounts = bottles.map { bottle ->
            bottle.countIds.sumOf { id -> pillCountByCountId[id] ?: 0 }
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

        if (config.messageFormat == Hl7Format.DISPENSESURE_NATIVE) {
            return buildDispenseMessageNative(
                txn = txn,
                txnDetails = txnDetails,
                drugCode = drugCode,
                scannedDrugCode = scannedDrugCode,
                drugName = drugName,
                drugCodeLabel = drugCodeLabel,
                pharmacistFamilyName = pharmacistFamilyName,
                pharmacistGivenName = pharmacistGivenName,
                isControlledSubstance = isControlledSubstance,
                isHazardousDrug = isHazardousDrug,
                isNdcVerified = isNdcVerified,
                config = config,
                builder = builder,
                now = now,
                messageId = messageId,
                orderId = orderId,
                totalCount = totalCount,
            )
        }

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

            when (config.messageFormat) {
                // Field mapping verified against Vivid's response-parse spec (ZUI-1 drugId,
                // ZUI-2 VerifiedBy truncated to 10 chars, ZUI-4 RxNo-RefillNo composite,
                // ZUI-6 qtyDispensed, ZUI-7 fill status, ZUI-9/10/11 lot/serial/expiry).
                // ZUI-8 (Base64 tray image log) populated below via drugImages.
                Hl7Format.VIVID -> zui { z ->
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
                Hl7Format.EYECON -> {
                    // EyeCon uses a 25-field fixed ZUI layout that the ZUIDispenseBuilder
                    // (11 fields max) cannot express. Use the builder for ZUI-1 only so the
                    // segment appears in the encoded message, then replace the whole line with
                    // the correct 25-field segment in buildEyeConZuiSegment below.
                    zui { z -> z.ndc = drugCode.replace("-", "") }
                }
                else -> Unit
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
            encoded.replace(Regex("ZUI\\|[^\r\n]*"), zuiSegment)
        } else {
            encoded
        }
    }

    private fun buildDispenseMessageNative(
        txn: DispenseTxnEntity,
        txnDetails: List<DispenseCountEntity>,
        drugCode: String,
        scannedDrugCode: String,
        drugName: String,
        drugCodeLabel: String,
        pharmacistFamilyName: String?,
        pharmacistGivenName: String?,
        isControlledSubstance: Boolean,
        isHazardousDrug: Boolean,
        isNdcVerified: Boolean,
        config: HL7Config,
        builder: org.rite.hl7.builder.HL7Builder,
        now: String,
        messageId: String,
        orderId: String,
        totalCount: Int,
    ): String {
        // ZDS-30 rows are "/images/<path>^<label>^<drugCode>", matching iOS. The old
        // "ZDS:<filename>" form carried no label and no drug code, so a receiver could not tell
        // which image was which, nor which drug it belonged to.
        val stepImages = txnDetails.mapNotNull { detail ->
            detail.imagePath?.let { path -> "/images/$path^${detail.step.toImageLabel()}^$drugCode" }
        }
        val rawCropImages = txnDetails.mapNotNull { detail ->
            detail.rawImagePath
                ?.takeIf { detail.step == StepState.TARGET_VERIFICATION.name }
                ?.let { raw -> "/images/$raw^dispense_count_raw^$drugCode" }
        }
        val bottles = DispenseBottleJson.decode(txn.bottlesJson)
        val barcodeImages = bottles
            .mapNotNull { it.barcodeImagePath?.takeIf { path -> path.isNotBlank() } }
            .map { path -> "/images/$path^Barcode Image^$drugCode" }
        val imageList = stepImages + rawCropImages + barcodeImages

        // ZDS-24: one "ndc^qty^lot^expiry^serial^" row per scanned bottle, so the PMS can see
        // which stock bottles the fill came from. Android was sending none.
        val dispensedDetails = txnDetails.filter { it.step.isDispensedQuantityStep() }
        val pillCountByDetailId = dispensedDetails.associate { it.countId to (it.pillCount ?: 0) }
        val bottleDetails = bottles.map { bottle ->
            val qty = bottle.countIds.sumOf { id -> pillCountByDetailId[id] ?: 0 }
            listOf(
                drugCode,
                qty.toString(),
                bottle.lotNumber.orEmpty(),
                bottle.expirationDate.orEmpty(),
                bottle.serialNumber.orEmpty(),
                "",
            ).joinToString("^")
        }

        val message = builder.rdsO13Ds {
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

            zds { z ->
                z.flowType = "DR"
                z.layoutVersion = "1.0"
                z.responseMessageId = messageId
                z.requestMessageId = txn.hl7MessageControlId?.takeIf { it.isNotBlank() }
                z.responseType = "RE"
                // ZDS-07 is "code^name" — the name is what a pharmacist reads on the PMS side,
                // and sending the bare code left it blank.
                z.drugs = listOf(if (drugName.isBlank()) drugCode else "$drugCode^$drugName")
                z.drugCodeType = drugCodeLabel
                z.operatorFamilyName = pharmacistFamilyName
                z.operatorGivenName = pharmacistGivenName
                z.prescriptionNumber = orderId
                z.orderReferenceNumber = txn.transactionOrderId
                z.fillNumber = txn.refillNo ?: "0"
                z.priority = txn.priority?.name
                z.dispensedQuantity = totalCount.toString()
                z.fillStatus = txn.targetCount.let { target ->
                    when {
                        target == null || totalCount == target -> "Complete"
                        totalCount > target -> "Over"
                        else -> "Partial"
                    }
                }
                // The count's real start, not the send time — sending `now` for both made every
                // dispense look instantaneous.
                z.startTime = txn.createdAt.takeIf { it > 0 }?.let { hl7Timestamp(it) } ?: now
                z.finishTime = now
                z.transactionId = txn.txnId.toString()
                // A caret in free-form operator text must stay escaped, or the ZDS-wide unescape
                // below would turn it into a component separator and shift every later field.
                z.note = txn.note?.replace("^", " ")
                z.unitOfMeasure = "TAB"
                if (bottleDetails.isNotEmpty()) z.bottleDetails = bottleDetails
                z.verificationStatus = if (isNdcVerified) "V" else "U"
                z.requestedNdc = drugCode
                z.dispensedNdc = scannedDrugCode
                z.controlledSubstanceFlag = if (isControlledSubstance) "Y" else "N"
                z.hazardousDrugFlag = if (isHazardousDrug) "Y" else "N"
                if (imageList.isNotEmpty()) z.imageList = imageList
                z.facilityNumber = config.sendingFacility
            }
        }

        // HL7 2.3.1 names the dispense trigger RDS^O01 and 2.5.1 names it RDS^O13, but the
        // native spec requires O13 at every version. This pins it; at 2.5.1 it matches nothing.
        val encoded = message.encode().replace("RDS^O01", "RDS^O13")
        // drugs, bottleDetails and imageList carry '^' as a real component separator, but the
        // library escapes it to \S\ in every component. Unescape ZDS lines so the PMS can parse
        // them — the same step the inventory ZDS needs, and which iOS applies here too.
        return encoded.split("\r").joinToString("\r") { line ->
            if (line.startsWith("ZDS")) line.replace("\\S\\", "^") else line
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
        bottles: List<DispenseBottle>,
        details: List<DispenseCountEntity>
    ): List<List<String>>? {
        val stepImages = details
            .filter { it.step == StepState.TARGET_VERIFICATION.name || it.step == StepState.VIAL.name }
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
        bottles: List<DispenseBottle>,
        details: List<DispenseCountEntity>
    ): List<ObxRow> {

        // Each detail's raw (unannotated) image gets its own row right after it.
        val detailImages = details.flatMap { detail ->
            val type = detail.step.toImageLabel()
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
     * Builds one ZSN row per bottle recorded on the transaction ([DispenseBottle] decoded from
     * [DispenseTxnEntity.bottlesJson]). Falls back to a single row built from the
     * caller-supplied [lotNumber]/[expirationDate]/[serialNumber]/[totalCount] when the
     * transaction has no bottle entries (legacy txns predating bottle tracking, or a txn
     * whose pills were all counted before any bottle was ever scanned).
     */
    private fun buildZSN(
        drugCode: String,
        bottles: List<DispenseBottle>,
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
        expectedCount: Int? = null
    ): List<NoteRow> {
        var comment = "Transaction Id: $txnId | Status: Completed | Total Count: $totalCount"

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
}
