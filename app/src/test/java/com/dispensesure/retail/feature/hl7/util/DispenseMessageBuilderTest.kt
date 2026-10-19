package com.dispensesure.retail.feature.hl7.util

import android.util.Base64
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottleJson
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
 * Unit tests for [DispenseMessageBuilder].
 *
 * The entities involved (DispenseTxnEntity, DispenseCountEntity)
 * are plain Kotlin data classes, so they are constructed directly without
 * mocking. The only Android dependency is [android.os.Build.MODEL], a static String
 * field that reads as null on the JVM (resulting in "PillCounter-null"), which is fine.
 *
 * The builder methods return raw wire-encoded HL7 strings (not structured objects), so
 * assertions check for the presence/absence of expected segment names and field values
 * in the encoded text.
 */
class DispenseMessageBuilderTest {

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
    ): DispenseTxnEntity = DispenseTxnEntity(
        txnId = txnId,
        status = CountStatus.COMPLETED,
        note = note,
        bottlesJson = barcodeImage?.let {
            DispenseBottleJson.encode(
                listOf(
                    DispenseBottle(
                        txnId = txnId,
                        barcodeImagePath = it,
                        countIds = bottleTxnDetailsIds,
                    )
                )
            )
        },
        rxNo = rxNo,
    )

    private fun detail(
        countId: Long = 0L,
        pillCount: Int? = null,
        step: String? = null,
        imagePath: String? = null,
        rawImagePath: String? = null,
    ): DispenseCountEntity = DispenseCountEntity(
        countId = countId,
        pillCount = pillCount,
        step = step,
        imagePath = imagePath,
        rawImagePath = rawImagePath,
    )

    // ============================ DISPENSE ============================

    @Test
    fun `buildDispenseMessage with rxNo null uses txnId and adds barcode obx`() {
        val details = listOf(
            detail(countId = 1L, pillCount = 10, step = StepState.TARGET_VERIFICATION.name, imagePath = "/a/b/img1.png"),
            // null pillCount/type/imagePath -> defaults 0/UNKNOWN/""
            detail(countId = 2L, pillCount = null, step = null, imagePath = null),
        )
        val txn = txn(
            txnId = 555L,
            rxNo = null,
            barcodeImage = "/storage/images/barcode_555.png",
            bottleTxnDetailsIds = listOf(1L, 2L),
        )

        val raw = DispenseMessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "12345-678-90",
            scannedDrugCode = "12345-678-90",
            drugName = "Atorvastatin",
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
            detail(pillCount = 5, step = "partial", imagePath = "/x/y/z/photo.jpg"),
        )

        val raw = DispenseMessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "NDC1",
            scannedDrugCode = "NDC1",
            drugName = "Drug",
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
            detail(pillCount = 20, step = StepState.TARGET_VERIFICATION.name, imagePath = "/a/img.png"),
        )

        val raw = DispenseMessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "12345-678-90",
            scannedDrugCode = "12345-678-90",
            drugName = "Atorvastatin",
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
            detail(countId = 1L, pillCount = 15, step = StepState.TARGET_VERIFICATION.name, imagePath = imgFile.absolutePath),
        )

        val raw = DispenseMessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "11111-222-33",
            scannedDrugCode = "11111-222-33",
            drugName = "Drug",
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
    fun `buildDispenseMessage for Vivid format sends the raw image right after its processed one`() {
        val img = File.createTempFile("zui8_img", ".png").apply { writeBytes(byteArrayOf(1)); deleteOnExit() }
        val raw = File.createTempFile("zui8_raw", ".png").apply { writeBytes(byteArrayOf(2)); deleteOnExit() }
        val details = listOf(
            detail(
                countId = 1L, pillCount = 15, step = StepState.TARGET_VERIFICATION.name,
                imagePath = img.absolutePath, rawImagePath = raw.absolutePath
            ),
        )

        val msg = DispenseMessageBuilder.buildDispenseMessage(
            txn = txn(txnId = 654L, rxNo = "RX-V1", note = null),
            txnDetails = details,
            drugCode = "11111-222-33",
            scannedDrugCode = "11111-222-33",
            drugName = "Drug",
            pharmacistName = null,
            config = HL7Config.current(
                selectedTerminalName = "PILLCOUNTER",
                pmsHostName = "PMS",
                hl7Format = Hl7Format.VIVID,
                sendingApplicationName = Hl7Format.VIVID.sendingApplication,
            ),
        )

        val groups = msg.lineSequence().first { it.startsWith("ZUI|") }.split("|")[8].split("^")
        assertEquals(2, groups.size)
        assertEquals(listOf("1B1", "1C2", java.util.Base64.getEncoder().encodeToString(byteArrayOf(1))), groups[0].split("&"))
        assertEquals(listOf("1B1", "2C2", java.util.Base64.getEncoder().encodeToString(byteArrayOf(2))), groups[1].split("&"))
    }

    @Test
    fun `buildDispenseMessage adds a _raw OBX row after the processed image row`() {
        val details = listOf(
            detail(
                countId = 1L, pillCount = 10, step = StepState.TARGET_VERIFICATION.name,
                imagePath = "/a/txn_detail_1.jpg", rawImagePath = "/a/txn_detail_1_raw.jpg"
            ),
            detail(countId = 2L, pillCount = 2, step = StepState.VIAL.name, imagePath = "/a/vial.jpg"),
        )

        val msg = DispenseMessageBuilder.buildDispenseMessage(
            txn = txn(txnId = 1L, rxNo = "RX1", note = null),
            txnDetails = details,
            drugCode = "NDC1",
            scannedDrugCode = "NDC1",
            drugName = "Drug",
            pharmacistName = null,
            location = null,
        )

        val obx = msg.lineSequence().filter { it.contains("|RP|IMG") }.toList()
        assertEquals(3, obx.size)
        assertTrue(obx[0].contains("IMG001") && obx[0].contains("images/txn_detail_1.jpg"))
        assertTrue(obx[1].contains("IMG002") && obx[1].contains("_raw") && obx[1].contains("images/txn_detail_1_raw.jpg"))
        assertTrue(obx[2].contains("IMG003") && obx[2].contains("images/vial.jpg"))
    }

    @Test
    fun `buildDispenseMessage for Vivid format leaves ZUI-8 empty when there are no images`() {
        val txn = txn(txnId = 111L, rxNo = "RX-V2", note = null)
        val details = listOf(
            detail(pillCount = 3, step = "fixed", imagePath = null),
        )

        val raw = DispenseMessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "NDC",
            scannedDrugCode = "NDC",
            drugName = "Drug",
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

    // ============================ DISPENSED QUANTITY ============================

    @Test
    fun `buildDispenseMessage emits RXD-10 as name only with no id component`() {
        val details = listOf(
            detail(countId = 1L, pillCount = 12, step = StepState.TARGET_VERIFICATION.name)
        )

        val raw = DispenseMessageBuilder.buildDispenseMessage(
            txn = txn(txnId = 777L, rxNo = "RX-1", note = null),
            txnDetails = details,
            drugCode = "12345-678-90",
            scannedDrugCode = "12345-678-90",
            drugName = "Atorvastatin",
            pharmacistName = "Jane Doe",
            pharmacistGivenName = "Jane",
            pharmacistFamilyName = "Doe",
        )

        val rxd = raw.lineSequence().first { it.startsWith("RXD|") }.split("|")
        assertEquals("^Doe^Jane", rxd[10])
    }

    @Test
    fun `buildDispenseMessage counts only the prescribed-count step`() {
        // One dispense of 23, surrounded by steps that measure other things:
        // a 60-pill pour-out, a 23-pill recount of the same pills, and a remainder count.
        val details = listOf(
            detail(countId = 1L, pillCount = 60, step = StepState.CONTAINER_INITIATE.name),
            detail(countId = 2L, pillCount = 20, step = StepState.TARGET_VERIFICATION.name),
            detail(countId = 3L, pillCount = 3, step = StepState.TARGET_VERIFICATION.name),
            detail(countId = 4L, pillCount = 23, step = StepState.TARGET_REVERIFICATION.name),
            detail(countId = 5L, pillCount = 0, step = StepState.VIAL.name),
            detail(countId = 6L, pillCount = 37, step = StepState.CONTAINER_PENDING.name),
        )
        val txn = txn(
            txnId = 900L,
            rxNo = "RX-COUNT",
            barcodeImage = "/storage/images/barcode_900.png",
            bottleTxnDetailsIds = listOf(1L, 2L, 3L, 4L, 5L, 6L),
            note = null,
        )

        val raw = DispenseMessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "00054-0244-24",
            scannedDrugCode = "00054-0244-24",
            drugName = "codeine sulfate 30mg tablet",
            pharmacistName = null,
        )

        // RXD-4 actualDispenseAmount: 20 + 3, not 143.
        assertEquals("23", raw.lineSequence().first { it.startsWith("RXD|") }.split("|")[4])
        // ZSN-7 quantityFromThisStockItem follows the same rule.
        assertEquals("23", raw.lineSequence().first { it.startsWith("ZSN|") }.split("|")[7])
        assertTrue(raw.contains("Total Count: 23"))

        // Every step still gets its image OBX row — only the counting changed.
        assertTrue(raw.contains("before_dispense_stock_bottle_count"))
        assertTrue(raw.contains("dispense_recount"))
    }

    @Test
    fun `buildDispenseMessage falls back to the step sum when no dispensed row is linked to a bottle`() {
        // linkCountToActiveBottle no-ops when the txn has no bottle entry yet, so a bottle
        // scanned after the pills were counted holds only the pour-out row. Summing the
        // bottle then gives 0 while the prescribed-count rows are sitting right there.
        val details = listOf(
            detail(countId = 1L, pillCount = 60, step = StepState.CONTAINER_INITIATE.name),
            detail(countId = 2L, pillCount = 30, step = StepState.TARGET_VERIFICATION.name),
        )
        val txn = txn(
            txnId = 901L,
            rxNo = "RX-UNLINKED",
            barcodeImage = "/storage/images/barcode_901.png",
            bottleTxnDetailsIds = listOf(1L),
            note = null,
        )

        val raw = DispenseMessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "00054-0244-24",
            scannedDrugCode = "00054-0244-24",
            drugName = "codeine sulfate 30mg tablet",
            pharmacistName = null,
        )

        // 30 from the prescribed-count step — never 0, and never the 60-pill pour-out.
        assertEquals("30", raw.lineSequence().first { it.startsWith("RXD|") }.split("|")[4])
        assertEquals("30", raw.lineSequence().first { it.startsWith("ZSN|") }.split("|")[7])
    }
}
