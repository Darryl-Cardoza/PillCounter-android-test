package com.rite.pillcounting.feature.hl7.util

import android.util.Base64
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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

    @Before
    fun setup() {
        // buildZuiImagePayload base64-encodes via android.util.Base64, a stub on the
        // unit-test JVM — route it to a real encoder so ZUI-8 image tests are hermetic.
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun txn(
        txnId: Long = 100L,
        rxNo: String? = null,
        barcodeImage: String? = null,
        note: String? = "some note",
        bottleTxnDetailsIds: List<Long> = emptyList(),
    ): PillCountTxnEntity = PillCountTxnEntity(
        txnId = txnId,
        isDispense = false,
        status = CountStatus.COMPLETED,
        note = note,
        bottleInfoListJson = barcodeImage?.let {
            BottleInfoJson.encode(
                listOf(
                    BottleInfo(
                        txnId = txnId,
                        barcodeImagePath = it,
                        txnDetailsIds = bottleTxnDetailsIds,
                    )
                )
            )
        },
        rxNo = rxNo,
    )

    private fun detail(
        txnDetailsId: Long = 0L,
        pillCount: Int? = null,
        type: String? = null,
        imagePath: String? = null,
    ): PillCountTxnDetailsEntity = PillCountTxnDetailsEntity(
        txnDetailsId = txnDetailsId,
        pillCount = pillCount,
        type = type,
        imagePath = imagePath,
    )

    // ============================ DISPENSE ============================

    @Test
    fun `buildDispenseMessage with rxNo null uses txnId and adds barcode obx`() {
        val details = listOf(
            detail(txnDetailsId = 1L, pillCount = 10, type = "fixed", imagePath = "/a/b/img1.png"),
            // null pillCount/type/imagePath -> defaults 0/UNKNOWN/""
            detail(txnDetailsId = 2L, pillCount = null, type = null, imagePath = null),
        )
        val txn = txn(
            txnId = 555L,
            rxNo = null,
            barcodeImage = "/storage/images/barcode_555.png",
            bottleTxnDetailsIds = listOf(1L, 2L),
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

        // 2 detail OBX + 1 barcode OBX, each an RP-type OBX pointing at images/<file>
        assertTrue(raw.contains("|RP|IMG001^"))
        assertTrue(raw.contains("images/img1.png"))
        assertTrue(raw.contains("|RP|IMG002^"))
        assertTrue("barcode OBX should be present", raw.contains("Barcode Image"))
        assertTrue(raw.contains("|RP|IMG003^"))
        assertTrue(raw.contains("images/barcode_555.png"))

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
        assertTrue(raw.contains("images/photo.jpg"))
        assertEquals(1, Regex("\\|RP\\|IMG").findAll(raw).count())

        // note null propagated: label present, no "Note:" suffix appended
        assertTrue(raw.contains("Transaction Id: 777"))
        assertTrue(!raw.contains("Note:"))
    }

    // ============================ EYECON ZUI ============================

    @Test
    fun `buildDispenseMessage for EyeCon format emits 25-field ZUI with no ZNI`() {
        val txn = txn(
            txnId = 321L,
            rxNo = "RX-EC1",
            note = null,
        )
        val details = listOf(
            detail(pillCount = 20, type = "fixed", imagePath = "/a/img.png"),
        )

        val raw = HL7MessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "12345-678-90",
            scannedDrugCode = "12345-678-90",
            drugName = "Atorvastatin",
            pharmacistId = "PH1",
            pharmacistName = "Jane Doe",
            pharmacistGivenName = "Jane",
            pharmacistFamilyName = "Doe",
            isNdcVerified = true,
            config = HL7Config.current(
                selectedTerminalName = "PILLCOUNTER",
                pmsHostName = "PMS",
                hl7Format = Hl7Format.EYECON,
                sendingApplicationName = Hl7Format.EYECON.sendingApplication,
            ),
        )

        assertTrue("no ZNI segment should be emitted for EyeCon", !raw.contains("ZNI"))

        val zuiLine = raw.lineSequence().first { it.startsWith("ZUI|") }
        val fields = zuiLine.split("|")

        // ZUI-1 ndc, ZUI-2 drugName, ZUI-3 userName (first name only), ZUI-4 prescriptionNumber,
        // ZUI-5 fillNumber, ZUI-6 verifiedBy, ZUI-7 stockBottleVerification (A = verified).
        assertEquals("1234567890", fields[1])
        assertEquals("Atorvastatin", fields[2])
        assertEquals("Jane", fields[3])
        assertEquals("RX-EC1", fields[4])
        assertEquals("1", fields[5])
        assertEquals("Jane", fields[6])
        assertEquals("A", fields[7])

        // ZUI-18 dispensedQuantity, ZUI-19 fillStatus, ZUI-21 stockBottleBarcodeNdc — the
        // fields PMS's C# parser actually reads.
        assertEquals("20", fields[18])
        assertEquals("complete", fields[19])
        assertEquals("1234567890", fields[21])

        // Fixed 25-field layout regardless of how few trailing fields carry values.
        assertEquals(25, fields.size - 1)
    }

    // ============================ VIVID ZUI-8 IMAGES ============================

    @Test
    fun `buildDispenseMessage for Vivid format inlines base64 tray images in ZUI-8`() {
        val imgFile = File.createTempFile("zui8_step", ".png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            deleteOnExit()
        }
        val barcodeFile = File.createTempFile("zui8_barcode", ".png").apply {
            writeBytes(byteArrayOf(5, 6, 7, 8))
            deleteOnExit()
        }

        val txn = txn(
            txnId = 654L,
            rxNo = "RX-V1",
            barcodeImage = barcodeFile.absolutePath,
            note = null,
        )
        val details = listOf(
            detail(txnDetailsId = 1L, pillCount = 15, type = StepState.TARGET_VERIFICATION.name, imagePath = imgFile.absolutePath),
        )

        val raw = HL7MessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "11111-222-33",
            scannedDrugCode = "11111-222-33",
            drugName = "Drug",
            pharmacistId = null,
            pharmacistName = null,
            config = HL7Config.current(
                selectedTerminalName = "PILLCOUNTER",
                pmsHostName = "PMS",
                hl7Format = Hl7Format.VIVID,
                sendingApplicationName = Hl7Format.VIVID.sendingApplication,
            ),
        )

        val zuiLine = raw.lineSequence().first { it.startsWith("ZUI|") }
        val zui8 = zuiLine.split("|")[8]

        // Two image groups (step image + barcode image), '^'-joined; each group is
        // batch/count/base64Data '&'-joined per buildZuiImagePayload's wire format.
        val groups = zui8.split("^")
        assertEquals(2, groups.size)

        val firstGroupParts = groups[0].split("&")
        assertEquals("1B1", firstGroupParts[0])
        assertEquals("1C2", firstGroupParts[1])
        assertEquals(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)), firstGroupParts[2])

        val secondGroupParts = groups[1].split("&")
        assertEquals("1B1", secondGroupParts[0])
        assertEquals("2C2", secondGroupParts[1])
        assertEquals(java.util.Base64.getEncoder().encodeToString(byteArrayOf(5, 6, 7, 8)), secondGroupParts[2])
    }

    @Test
    fun `buildDispenseMessage for Vivid format leaves ZUI-8 empty when there are no images`() {
        val txn = txn(txnId = 111L, rxNo = "RX-V2", note = null)
        val details = listOf(
            detail(pillCount = 3, type = "fixed", imagePath = null),
        )

        val raw = HL7MessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "NDC",
            scannedDrugCode = "NDC",
            drugName = "Drug",
            pharmacistId = null,
            pharmacistName = null,
            config = HL7Config.current(
                selectedTerminalName = "PILLCOUNTER",
                pmsHostName = "PMS",
                hl7Format = Hl7Format.VIVID,
                sendingApplicationName = Hl7Format.VIVID.sendingApplication,
            ),
        )

        val zuiLine = raw.lineSequence().first { it.startsWith("ZUI|") }
        val fields = zuiLine.split("|")
        // ZUI-8 has no images -> field emitted empty (drugImages was null).
        assertEquals("", fields.getOrElse(8) { "" })
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
