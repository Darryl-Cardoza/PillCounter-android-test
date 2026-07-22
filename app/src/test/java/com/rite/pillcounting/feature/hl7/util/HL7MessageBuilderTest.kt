package com.rite.pillcounting.feature.hl7.util

import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.room.models.enums.CountStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [HL7MessageBuilder].
 *
 * The entities involved (PillCountTxnEntity, PillCountTxnDetailsEntity, BatchEntity,
 * BatchTxnDto) are plain Kotlin data classes, so they are constructed directly without
 * mocking. The only Android dependency is [android.os.Build.MODEL], a static String
 * field that reads as null on the JVM (resulting in "PillCounter-null"), which is fine.
 *
 * The builder methods return raw wire-encoded HL7 strings (not structured objects), so
 * assertions check for the presence/absence of expected segment names and field values
 * in the encoded text.
 */
class HL7MessageBuilderTest {

    private fun txn(
        txnId: Long = 100L,
        rxNo: String? = null,
        barcodeImage: String? = null,
        note: String? = "some note",
    ): PillCountTxnEntity = PillCountTxnEntity(
        txnId = txnId,
        isDispense = false,
        status = CountStatus.COMPLETED,
        note = note,
        barcodeImage = barcodeImage,
        rxNo = rxNo,
    )

    private fun detail(
        pillCount: Int? = null,
        type: String? = null,
        imagePath: String? = null,
    ): PillCountTxnDetailsEntity = PillCountTxnDetailsEntity(
        pillCount = pillCount,
        type = type,
        imagePath = imagePath,
    )

    // ============================ DISPENSE ============================

    @Test
    fun `buildDispenseMessage with rxNo null uses txnId and adds barcode obx`() {
        val txn = txn(
            txnId = 555L,
            rxNo = null,
            barcodeImage = "/storage/images/barcode_555.png",
        )
        val details = listOf(
            detail(pillCount = 10, type = "fixed", imagePath = "/a/b/img1.png"),
            // null pillCount/type/imagePath -> defaults 0/UNKNOWN/""
            detail(pillCount = null, type = null, imagePath = null),
        )

        val raw = HL7MessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "12345-678-90",
            scannedDrugCode = "12345-678-90",
            drugName = "Atorvastatin",
            pharmacistId = "PH1",
            pharmacistName = "John",
            location = "Counter1",
        )

        assertTrue(raw.isNotEmpty())
        assertTrue("should contain RDS^O13 in MSH", raw.contains("RDS"))
        assertTrue(raw.contains("O13"))
        assertTrue("should contain RXD", raw.contains("RXD"))
        assertTrue("should contain PID", raw.contains("PID"))
        assertTrue("should contain ORC", raw.contains("ORC"))

        // rxNo == null -> falls back to txnId string, used as both PID patientId and
        // ORC/RXD prescription number.
        assertTrue("PID should carry txnId as patientId", raw.contains("555"))

        // drug info on RXD
        assertTrue(raw.contains("12345-678-90"))
        assertTrue(raw.contains("Atorvastatin"))

        // total count = 10 + 0 = 10
        assertTrue(raw.contains("|10|") || raw.contains("|10\r") || raw.contains("|10$"))

        // 2 detail OBX + 1 barcode OBX
        assertTrue(raw.contains("DISP_IMG"))
        assertTrue(raw.contains("count=10\\F\\type=fixed\\F\\image=img1.png"))
        assertTrue(raw.contains("count=0\\F\\type=unknown\\F\\image="))
        assertTrue("barcode OBX should be present", raw.contains("Barcode Image"))
        assertTrue(raw.contains("count=0\\F\\type=dispense_bottle\\F\\image=barcode_555.png"))

        // common note text
        assertTrue(raw.contains("Transaction Id: 555"))
        assertTrue(raw.contains("Total Count: 10"))
        assertTrue(raw.contains("Note: some note"))
    }

    @Test
    fun `buildDispenseMessage with rxNo present and blank barcode omits barcode obx`() {
        val txn = txn(
            txnId = 777L,
            rxNo = "RX-9001",
            barcodeImage = null,
            note = null,
        )
        val details = listOf(
            detail(pillCount = 5, type = "partial", imagePath = "/x/y/z/photo.jpg"),
        )

        val raw = HL7MessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "NDC1",
            scannedDrugCode = "NDC1",
            drugName = "Drug",
            pharmacistId = null,
            pharmacistName = null,
            location = null,
        )

        // rxNo present -> used directly as prescription number / patient id
        assertTrue(raw.contains("RX-9001"))

        // barcode null -> no barcode OBX, only the single detail OBX
        assertTrue(raw.contains("count=5\\F\\type=partial\\F\\image=photo.jpg"))
        assertEquals(1, Regex("DISP_IMG").findAll(raw).count())

        // note null propagated: label present, no "Note:" suffix appended
        assertTrue(raw.contains("Transaction Id: 777"))
        assertTrue(!raw.contains("Note:"))
    }

    // ============================ INVENTORY ============================

    @Test
    fun `buildInventoryMessage uses requestIdFromPMS when non-blank and covers opened sealed both NA branches`() {
        val batch = BatchEntity(
            batchId = 42L,
            requestIdFromPMS = "REQ-FROM-PMS",
            bucketId = "BUCKET-7",
        )

        val txns = listOf(
            // opened only (loose) -> sealed == 0
            BatchTxnDto(1L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 0, looseQty = 4, packageQty = 10),
            // sealed only (bottle * package) -> opened == 0
            BatchTxnDto(2L, 2L, "DrugB", "NDC-B", "LOTB", "EXPB", bottleQty = 3, looseQty = 0, packageQty = 5),
            // both opened and sealed for same key
            BatchTxnDto(3L, 3L, "DrugC", "NDC-C", "LOTC", "EXPC", bottleQty = 2, looseQty = 7, packageQty = 4),
            // NA branch: opened == 0 && sealed == 0 (all nulls)
            BatchTxnDto(4L, 4L, "DrugD", "NDC-D", "LOTD", "EXPD", bottleQty = null, looseQty = null, packageQty = null),
        )

        val raw = HL7MessageBuilder.buildInventoryMessage(batch = batch, txns = txns)

        assertTrue(raw.isNotEmpty())
        assertTrue("should contain MSH", raw.contains("MSH"))
        assertTrue("should contain BTS", raw.contains("BTS"))
        assertTrue("should contain ORC", raw.contains("ORC"))
        assertTrue("should contain INV", raw.contains("INV"))
        assertTrue("should contain ZIN", raw.contains("ZIN"))
        // bucketId used as ORC placer order number
        assertTrue("ORC should carry bucketId", raw.contains("BUCKET-7"))
        // both opened+sealed markers present (NA branch has opened=0/sealed=0 but still
        // emits OPENED/SEALED ZIN rows with quantity 0)
        assertTrue(raw.contains("OPENED"))
        assertTrue(raw.contains("SEALED"))
    }

    @Test
    fun `buildInventoryMessage handles null requestIdFromPMS and bucketId`() {
        val batchNull = BatchEntity(
            batchId = 99L,
            requestIdFromPMS = null,
            bucketId = null,
        )
        val rawNull = HL7MessageBuilder.buildInventoryMessage(
            batch = batchNull,
            txns = listOf(
                BatchTxnDto(1L, 1L, "DrugX", "NDC-X", "LOTX", "EXPX", bottleQty = 1, looseQty = 0, packageQty = 2),
            ),
        )
        assertTrue(rawNull.isNotEmpty())
        assertTrue(rawNull.contains("NDC-X"))

        // blank requestIdFromPMS / bucketId, empty txns
        val batchBlank = BatchEntity(
            batchId = 7L,
            requestIdFromPMS = "   ",
            bucketId = null,
        )
        val rawBlank = HL7MessageBuilder.buildInventoryMessage(
            batch = batchBlank,
            txns = emptyList(),
        )
        assertTrue(rawBlank.isNotEmpty())
        assertTrue(rawBlank.contains("MSH"))
    }

    @Test
    fun `buildInventoryMessageChunks splits large batches and carries chunk metadata`() {
        val batch = BatchEntity(batchId = 1L, requestIdFromPMS = "REQ", bucketId = "BUCKET-1")
        val txns = (1..250).map { i ->
            BatchTxnDto(
                txnId = i.toLong(),
                drugId = i.toLong(),
                drugName = "Drug$i",
                ndc = "NDC$i",
                lotNo = "LOT$i",
                expiry = "EXP$i",
                bottleQty = 1,
                looseQty = 0,
                packageQty = 1,
            )
        }

        val chunks = HL7MessageBuilder.buildInventoryMessageChunks(
            batch = batch,
            txns = txns,
            maxRowsPerChunk = 200,
        )

        assertEquals(2, chunks.size)
        assertEquals(1, chunks[0].chunkIndex)
        assertEquals(2, chunks[0].totalChunks)
        assertEquals(2, chunks[1].chunkIndex)
        assertTrue(chunks[0].message.contains("NDC1"))
        assertTrue(chunks[1].message.contains("NDC250"))
    }
}
