package com.dispensesure.retail.feature.hl7.util

import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.dtos.StockBottleInfoDto
import org.rite.hl7.HL7
import java.io.File

/**
 * Builds the INU^U05 inventory response, split into chunks for large batches.
 * Like [DispenseMessageBuilder], it does not send, touch the database or use coroutines.
 */
object InventoryMessageBuilder {

    // =========================================================
    // DISPENSESURE_NATIVE INVENTORY ACK (MSH + ZDS, flowType=INVENTORY)
    // Response to INR^U06 with ZDR(flowType=INVENTORY) — sent immediately after txn creation.
    // Shape: MSH + ZDS only (matching NATIVE dispense response convention).
    // =========================================================

    data class ZdrInventoryCount(
        val ndc: String,
        val total: Int,
        val sealed: Int,
        val sealedBottles: Int,
        val opened: Int,
        val openedBottles: Int,
        val lot: String = "",
        val expiry: String = "",
    )

    fun buildZdrInventoryAck(
        requestMessageId: String,
        orderReferenceNumber: String = "",
        ndcs: List<String>,
        counts: List<ZdrInventoryCount> = emptyList(),
        config: HL7Config = HL7Config.current("PILLCOUNTER", "PMS"),
    ): String {
        val now = hl7TimestampNow()
        val messageId = "INR-ACK-${System.currentTimeMillis() / 1000}"
        val hl7 = HL7(org.rite.hl7.config.HL7Config(org.rite.hl7.config.HL7Version.from(config.versionId)))
        val builder = hl7.build()

        val inventoryCountStrings = counts.map { c ->
            // Format: ndc^total^sealed^sealedBottles^opened^openedBottles^lot^expiry
            "${c.ndc}^${c.total}^${c.sealed}^${c.sealedBottles}^${c.opened}^${c.openedBottles}^${c.lot}^${c.expiry}"
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
                z.flowType = "IR"
                z.layoutVersion = "1.0"
                z.responseMessageId = messageId
                z.requestMessageId = requestMessageId
                z.responseType = "RE"
                z.drugs = ndcs
                z.drugCodeType = "NDC"
                z.orderReferenceNumber = orderReferenceNumber.takeIf { it.isNotBlank() }
                z.unitOfMeasure = "TAB"
                z.inventoryCounts = inventoryCountStrings
            }
        }

        // Library emits RDS^O01 for HL7 < 2.5; spec requires RDS^O13 — fix the message type.
        return message.encode().replace("RDS^O01", "RDS^O13")
    }

    // =========================================================
    // INVENTORY RESPONSE (INU^U05, INV + ZAD)
    // =========================================================

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
     * only drive local resume bookkeeping — see [StockHl7Repository].
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
     * column ever lands, this and `StockCountViewModel.toRecentRows` change together.
     *
     * ORC-2 carries this batch's bucketId, and — when this batch answers a
     * PMS-originated INR^U06 request (`batch.requestIdFromPMS` non-blank) — that
     * request id appended as `<bucketId>^<requestIdFromPMS>` so PMS can correlate
     * this response with its original request. Chunks must be sent in order, each
     * one only after the previous chunk's ACK — see StockHl7Repository.
     */
    fun buildInventoryMessageChunks(
        batch: StockBatchEntity,
        bottles: List<StockBottleInfoDto>,
        config: HL7Config = HL7Config.current("PILLCOUNTER", "PMS"),
        maxRowsPerChunk: Int = 200
    ): List<InventoryChunk> {

        val now = hl7TimestampNow()
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
        bottles.forEach { bottle ->
            val key = Key(
                ndc = bottle.ndc.orEmpty(),
                name = bottle.drugName.orEmpty(),
                lot = bottle.lotNo.orEmpty(),
                expiry = bottle.expiry.orEmpty()
            )
            val packageQty = bottle.packageQty ?: 0
            val e = grouped.getOrPut(key) { Qty() }
            e.opened += bottle.looseQty ?: 0
            e.sealed += (bottle.bottleQty ?: 0) * packageQty
            // No open-bottle column exists: a line with loose pills counts as one
            // open bottle, matching what the inventory screen already shows.
            e.sealedBottles += bottle.bottleQty ?: 0
            if ((bottle.looseQty ?: 0) > 0) e.openedBottles++
            bottle.imagePaths?.let { e.imagePaths.addAll(it) }
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

            val hl7 = HL7(org.rite.hl7.config.HL7Config(org.rite.hl7.config.HL7Version.from(config.versionId)))
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
}
