package com.rite.pillcounting.feature.hl7.util

import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * NOTE on coverage: the private fun buildRoomImageUrl(deviceIp, fileName, port) is never
 * called from any code path inside HL7MessageBuilder. It is unreachable dead code and
 * therefore cannot be exercised by these (or any) tests.
 */
class HL7MessageBuilderTest {

    private fun txn(
        txnId: Long = 100L,
        rxNo: String? = null,
        barcodeImage: String? = null,
        note: String? = "some note"
    ): PillCountTxnEntity = PillCountTxnEntity(
        txnId = txnId,
        countType = CountType.REGULAR,
        status = CountStatus.COMPLETED,
        note = note,
        barcodeImage = barcodeImage,
        rxNo = rxNo
    )

    private fun detail(
        pillCount: Int? = null,
        type: String? = null,
        imagePath: String? = null
    ): PillCountTxnDetailsEntity = PillCountTxnDetailsEntity(
        pillCount = pillCount,
        type = type,
        imagePath = imagePath
    )

    // ============================ DISPENSE ============================

    @Test
    fun `buildDispenseMessage with rxNo null uses txnId and adds barcode obx`() {
        val txn = txn(
            txnId = 555L,
            rxNo = null,
            barcodeImage = "/storage/images/barcode_555.png"
        )
        val details = listOf(
            detail(pillCount = 10, type = "fixed", imagePath = "/a/b/img1.png"),
            // null pillCount/type/imagePath -> defaults 0/UNKNOWN/""
            detail(pillCount = null, type = null, imagePath = null)
        )

        val msg = HL7MessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "12345-678-90",
            drugName = "Atorvastatin",
            pharmacistId = "PH1",
            pharmacistName = "John",
            location = "Counter1"
        )

        assertEquals("RDS", msg.messageType)
        assertEquals("O13", msg.triggerEvent)
        assertEquals("PillCounter-${android.os.Build.MODEL}", msg.sendingFacility)

        // rxNo == null -> falls back to txnId string
        assertEquals("555", msg.patient?.patientId)
        assertEquals("555", msg.order?.placerOrderId)
        assertEquals("RE", msg.order?.orderControl)
        assertEquals("CM", msg.order?.orderStatus)

        // single dispense, total count = 10 + 0
        assertEquals(1, msg.dispenses.size)
        val d = msg.dispenses.first()
        assertEquals("12345-678-90", d.drugCode)
        assertEquals("Atorvastatin", d.drugName)
        assertEquals("10", d.quantityDispensed)
        assertEquals("555", d.prescriptionNumber)
        assertEquals("PH1", d.pharmacistId)
        assertEquals("John", d.pharmacistGivenName)
        assertEquals("Counter1", d.deliverToLocation)
        assertEquals("some note", d.dispensingNotes)
        // Lot/expiry are no longer emitted on dispense after the stock-count normalization
        // (they lived only on stock rows and were always null for dispense).
        assertEquals(null, d.lotNumber)
        assertEquals(null, d.expirationDate)

        // 2 detail OBX + 1 barcode OBX
        assertEquals(3, msg.obxSegments.size)
        val first = msg.obxSegments[0]
        assertEquals("1", first.setId)
        assertEquals("DISP_IMG", first.observationId)
        assertEquals("Dispense Image 1", first.observationText)
        assertEquals("count=10|type=fixed|image=img1.png", first.observationValue)

        val second = msg.obxSegments[1]
        assertEquals("count=0|type=UNKNOWN|image=", second.observationValue)

        val barcode = msg.obxSegments[2]
        assertEquals("3", barcode.setId)
        assertEquals("Barcode Image", barcode.observationText)
        assertEquals("count=0|type=SCAN|image=barcode_555.png", barcode.observationValue)

        // common notes
        assertEquals(4, msg.notes.size)
        assertEquals("Total Count: 10", msg.notes[1].comment)
        assertEquals("Transaction Id: 555", msg.notes[2].comment)
        assertEquals("Note: some note", msg.notes[3].comment)

        // header + timestamp
        assertNotNull(msg.header)
        assertEquals("RDS", msg.header.messageType)
        assertEquals("O13", msg.header.triggerEvent)
        assertEquals(14, msg.timestamp.length) // yyyyMMddHHmmss
        assertNotNull(msg.messageId)
    }

    @Test
    fun `buildDispenseMessage with rxNo present and blank barcode omits barcode obx`() {
        val txn = txn(
            txnId = 777L,
            rxNo = "RX-9001",
            barcodeImage = null,
            note = null
        )
        val details = listOf(
            detail(pillCount = 5, type = "partial", imagePath = "/x/y/z/photo.jpg")
        )

        val msg = HL7MessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = details,
            drugCode = "NDC1",
            drugName = "Drug",
            pharmacistId = null,
            pharmacistName = null,
            location = null
        )

        // rxNo present -> used directly
        assertEquals("RX-9001", msg.patient?.patientId)
        assertEquals("RX-9001", msg.order?.placerOrderId)
        assertEquals("RX-9001", msg.dispenses.first().prescriptionNumber)

        // barcode null -> no barcode OBX, only the single detail OBX
        assertEquals(1, msg.obxSegments.size)
        assertEquals("count=5|type=partial|image=photo.jpg", msg.obxSegments[0].observationValue)
        assertEquals("5", msg.dispenses.first().quantityDispensed)

        // note null propagated
        assertEquals("Note: null", msg.notes[3].comment)
    }

    // ============================ INVENTORY ============================

    @Test
    fun `buildInventoryMessage uses requestIdFromPMS when non-blank and covers opened sealed both NA branches`() {
        val batch = BatchEntity(
            batchId = 42L,
            requestIdFromPMS = "REQ-FROM-PMS",
            bucketId = "BUCKET-7"
        )

        val txns = listOf(
            // opened only (loose) -> sealed == 0
            BatchTxnDto(1L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 0, looseQty = 4, packageQty = 10),
            // sealed only (bottle * package) -> opened == 0
            BatchTxnDto(2L, 2L, "DrugB", "NDC-B", "LOTB", "EXPB", bottleQty = 3, looseQty = 0, packageQty = 5),
            // both opened and sealed for same key
            BatchTxnDto(3L, 3L, "DrugC", "NDC-C", "LOTC", "EXPC", bottleQty = 2, looseQty = 7, packageQty = 4),
            // NA branch: opened == 0 && sealed == 0 (all nulls)
            BatchTxnDto(4L, 4L, "DrugD", "NDC-D", "LOTD", "EXPD", bottleQty = null, looseQty = null, packageQty = null)
        )

        val raw = HL7MessageBuilder.buildInventoryMessage(batch = batch, txns = txns)

        assertTrue(raw.isNotEmpty())
        assertTrue("should contain MSH", raw.contains("MSH"))
        assertTrue("should contain MSA", raw.contains("MSA"))
        assertTrue("should contain ORC", raw.contains("ORC"))
        assertTrue("should contain INV", raw.contains("INV"))
        assertTrue("should contain ZIN", raw.contains("ZIN"))
        // requestIdFromPMS used in MSA
        assertTrue("MSA should carry requestIdFromPMS", raw.contains("REQ-FROM-PMS"))
        // bucketId used as orderId in ORC
        assertTrue("ORC should carry bucketId", raw.contains("BUCKET-7"))
        // NA branch marker present
        assertTrue("should contain NA marker", raw.contains("NA"))
        // both opened+sealed markers
        assertTrue(raw.contains("OPENED"))
        assertTrue(raw.contains("SEALED"))
    }

    @Test
    fun `buildInventoryMessage falls back to REQ batchId when requestIdFromPMS null or blank`() {
        val batchNull = BatchEntity(
            batchId = 99L,
            requestIdFromPMS = null,
            bucketId = null
        )
        val rawNull = HL7MessageBuilder.buildInventoryMessage(
            batch = batchNull,
            txns = listOf(
                BatchTxnDto(1L, 1L, "DrugX", "NDC-X", "LOTX", "EXPX", bottleQty = 1, looseQty = 0, packageQty = 2)
            )
        )
        assertTrue(rawNull.isNotEmpty())
        assertTrue("should fall back to REQ<batchId>", rawNull.contains("REQ99"))

        // blank requestIdFromPMS also falls back
        val batchBlank = BatchEntity(
            batchId = 7L,
            requestIdFromPMS = "   ",
            bucketId = null
        )
        val rawBlank = HL7MessageBuilder.buildInventoryMessage(
            batch = batchBlank,
            txns = emptyList()
        )
        assertTrue(rawBlank.isNotEmpty())
        assertTrue("blank should fall back to REQ<batchId>", rawBlank.contains("REQ7"))
    }

    @Test
    fun `buildInventoryMessage with explicit requestId and orderId overrides defaults`() {
        val batch = BatchEntity(batchId = 1L, requestIdFromPMS = "IGNORED", bucketId = "IGNORED-BUCKET")

        val raw = HL7MessageBuilder.buildInventoryMessage(
            batch = batch,
            txns = listOf(
                BatchTxnDto(1L, 1L, null, null, null, null, bottleQty = 1, looseQty = 1, packageQty = 1)
            ),
            requestId = "EXPLICIT-REQ",
            orderId = "EXPLICIT-ORDER"
        )

        assertTrue(raw.contains("EXPLICIT-REQ"))
        assertTrue(raw.contains("EXPLICIT-ORDER"))
    }
}
