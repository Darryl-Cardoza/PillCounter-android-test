package com.dispensesure.retail.feature.hl7.util

import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.dtos.StockBottleInfoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InventoryMessageBuilder]. The builder returns wire-encoded HL7 text,
 * so the assertions look for segment names and field values in it.
 */
class InventoryMessageBuilderTest {

    // ============================ INVENTORY ============================

    @Test
    fun `buildInventoryMessage uses requestIdFromPMS when non-blank and covers opened sealed both NA branches`() {
        val batch = StockBatchEntity(
            batchId = 42L,
            requestIdFromPMS = "REQ-FROM-PMS",
            bucketId = "BUCKET-7",
        )

        val bottles = listOf(
            // opened only (loose) -> sealed == 0
            StockBottleInfoDto(1L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 0, looseQty = 4, packageQty = 10),
            // sealed only (bottle * package) -> opened == 0
            StockBottleInfoDto(2L, 2L, "DrugB", "NDC-B", "LOTB", "EXPB", bottleQty = 3, looseQty = 0, packageQty = 5),
            // both opened and sealed for same key
            StockBottleInfoDto(3L, 3L, "DrugC", "NDC-C", "LOTC", "EXPC", bottleQty = 2, looseQty = 7, packageQty = 4),
            // NA branch: opened == 0 && sealed == 0 (all nulls)
            StockBottleInfoDto(4L, 4L, "DrugD", "NDC-D", "LOTD", "EXPD", bottleQty = null, looseQty = null, packageQty = null),
        )

        val raw = InventoryMessageBuilder.buildInventoryMessage(batch = batch, bottles = bottles)

        assertTrue(raw.isNotEmpty())
        assertTrue("should contain MSH", raw.contains("MSH"))
        assertTrue("should contain ORC", raw.contains("ORC"))
        assertTrue("should contain INV", raw.contains("INV"))
        assertTrue("should contain SEALED_QTY", raw.contains("SEALED_QTY"))
        assertTrue("should contain OPEN_QTY", raw.contains("OPEN_QTY"))
        assertTrue("no BTS on the wire", !raw.contains("BTS"))
        assertTrue("no ZIN on the wire", !raw.contains("ZIN"))
        assertTrue("no ZAD on the wire", !raw.contains("ZAD"))
        assertTrue("no NTE on the wire", !raw.lineSequence().any { it.startsWith("NTE") })
        // bucketId + requestIdFromPMS carried on ORC-2.1/ORC-2.2
        val orcLine = raw.lineSequence().first { it.startsWith("ORC|") }
        assertEquals("RE|BUCKET-7^REQ-FROM-PMS", orcLine.removePrefix("ORC|"))
    }

    @Test
    fun `buildInventoryMessage handles null requestIdFromPMS and bucketId`() {
        val batchNull = StockBatchEntity(
            batchId = 99L,
            requestIdFromPMS = null,
            bucketId = null,
        )
        val rawNull = InventoryMessageBuilder.buildInventoryMessage(
            batch = batchNull,
            bottles = listOf(
                StockBottleInfoDto(1L, 1L, "DrugX", "NDC-X", "LOTX", "EXPX", bottleQty = 1, looseQty = 0, packageQty = 2),
            ),
        )
        assertTrue(rawNull.isNotEmpty())
        assertTrue(rawNull.contains("NDC-X"))

        // blank requestIdFromPMS / bucketId, empty bottles
        val batchBlank = StockBatchEntity(
            batchId = 7L,
            requestIdFromPMS = "   ",
            bucketId = null,
        )
        val rawBlank = InventoryMessageBuilder.buildInventoryMessage(
            batch = batchBlank,
            bottles = emptyList(),
        )
        assertTrue(rawBlank.isNotEmpty())
        assertTrue(rawBlank.contains("MSH"))
    }

    @Test
    fun `buildInventoryMessageChunks splits large batches and carries chunk metadata`() {
        val batch = StockBatchEntity(batchId = 1L, requestIdFromPMS = "REQ", bucketId = "BUCKET-1")
        val bottles = (1..250).map { i ->
            StockBottleInfoDto(
                bottleId = i.toLong(),
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

        val chunks = InventoryMessageBuilder.buildInventoryMessageChunks(
            batch = batch,
            bottles = bottles,
            maxRowsPerChunk = 200,
        )

        assertEquals(2, chunks.size)
        assertEquals(1, chunks[0].chunkIndex)
        assertEquals(2, chunks[0].totalChunks)
        assertEquals(2, chunks[1].chunkIndex)
        assertTrue(chunks[0].message.contains("NDC1"))
        assertTrue(chunks[1].message.contains("NDC250"))
    }

    @Test
    fun `buildInventoryMessage emits IMG rows only for groups with photos`() {
        val batch = StockBatchEntity(batchId = 5L, requestIdFromPMS = null, bucketId = "BUCKET-5")
        val bottles = listOf(
            StockBottleInfoDto(
                1L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA",
                bottleQty = 1, looseQty = 0, packageQty = 1,
                imagePaths = listOf("images/a1.jpg", "images/a2.jpg"),
            ),
            StockBottleInfoDto(
                2L, 2L, "DrugB", "NDC-B", "LOTB", "EXPB",
                bottleQty = 1, looseQty = 0, packageQty = 1,
                imagePaths = null,
            ),
        )

        val raw = InventoryMessageBuilder.buildInventoryMessage(batch = batch, bottles = bottles)

        assertTrue("IMG001 present for first image", raw.contains("IMG001"))
        assertTrue("IMG002 present for second image", raw.contains("IMG002"))
        assertTrue(raw.contains("images/a1.jpg"))
        assertTrue(raw.contains("images/a2.jpg"))

        val lines = raw.split("\r")
        val invLineA = lines.first { it.startsWith("INV|") && it.contains("NDC-A") }
        val invSetIdA = invLineA.split("|")[1]
        val obxForA = lines.filter { it.startsWith("OBX|") && it.split("|").getOrNull(4) == invSetIdA }
        // Group A has two images: two OBX rows, one per image.
        assertEquals(2, obxForA.count { it.contains("IMG00") })

        val invLineB = lines.first { it.startsWith("INV|") && it.contains("NDC-B") }
        val invSetIdB = invLineB.split("|")[1]
        // Group B has no images: no IMG OBX row linked to its INV Set-ID.
        val obxForB = lines.filter { it.startsWith("OBX|") && it.split("|").getOrNull(4) == invSetIdB }
        assertTrue(obxForB.none { it.contains("IMG00") })
    }

    @Test
    fun `buildInventoryMessage links OBX rows to parent INV Set-ID across multiple groups`() {
        val batch = StockBatchEntity(batchId = 6L, requestIdFromPMS = null, bucketId = "BUCKET-6")
        val bottles = listOf(
            StockBottleInfoDto(1L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 1, looseQty = 0, packageQty = 1),
            StockBottleInfoDto(2L, 2L, "DrugB", "NDC-B", "LOTB", "EXPB", bottleQty = 0, looseQty = 5, packageQty = 1),
        )

        val raw = InventoryMessageBuilder.buildInventoryMessage(batch = batch, bottles = bottles)
        val lines = raw.split("\r")

        val invLines = lines.filter { it.startsWith("INV|") }
        assertEquals(2, invLines.size)
        val setIdA = invLines[0].split("|")[1]
        val setIdB = invLines[1].split("|")[1]
        assertTrue(setIdA != setIdB)

        val sealedQtyRows = lines.filter { it.startsWith("OBX|") && it.contains("SEALED_QTY") }
        assertTrue(sealedQtyRows.any { it.split("|").getOrNull(4) == setIdA })
        assertTrue(sealedQtyRows.any { it.split("|").getOrNull(4) == setIdB })
    }

    @Test
    fun `buildInventoryMessage carries bottle count as the second component of SEALED_QTY and OPEN_QTY`() {
        val batch = StockBatchEntity(batchId = 8L, requestIdFromPMS = null, bucketId = "BUCKET-8")
        val bottles = listOf(
            // 2 sealed bottles x 1000 = 2000 sealed pills; 365 loose from this one open bottle
            StockBottleInfoDto(1L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 2, looseQty = 365, packageQty = 1000),
        )

        val raw = InventoryMessageBuilder.buildInventoryMessage(batch = batch, bottles = bottles)
        val lines = raw.split("\r")

        val sealedRow = lines.first { it.startsWith("OBX|") && it.contains("SEALED_QTY") }
        val openRow = lines.first { it.startsWith("OBX|") && it.contains("OPEN_QTY") }
        assertEquals("2000^2", sealedRow.split("|").getOrNull(5))
        assertEquals("365^1", openRow.split("|").getOrNull(5))

        // The separator must be a real component delimiter, not an escaped literal.
        assertTrue("SEALED_QTY separator must not be escaped", !sealedRow.contains("\\S\\"))
        assertTrue("OPEN_QTY separator must not be escaped", !openRow.contains("\\S\\"))
    }

    @Test
    fun `buildInventoryMessage emits zero bottle counts as 0 caret 0`() {
        val batch = StockBatchEntity(batchId = 9L, requestIdFromPMS = null, bucketId = "BUCKET-9")
        val bottles = listOf(
            StockBottleInfoDto(1L, 1L, "DrugD", "NDC-D", "LOTD", "EXPD", bottleQty = null, looseQty = null, packageQty = null),
        )

        val raw = InventoryMessageBuilder.buildInventoryMessage(batch = batch, bottles = bottles)
        val lines = raw.split("\r")

        val sealedRow = lines.first { it.startsWith("OBX|") && it.contains("SEALED_QTY") }
        val openRow = lines.first { it.startsWith("OBX|") && it.contains("OPEN_QTY") }
        assertEquals("0^0", sealedRow.split("|").getOrNull(5))
        assertEquals("0^0", openRow.split("|").getOrNull(5))
    }

    @Test
    fun `buildInventoryMessage counts one open bottle per line with loose pills in a group`() {
        val batch = StockBatchEntity(batchId = 10L, requestIdFromPMS = null, bucketId = "BUCKET-10")
        // Same drug/lot/expiry across three stock-txn rows, so all three land in one INV group.
        val bottles = listOf(
            StockBottleInfoDto(1L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 1, looseQty = 200, packageQty = 100),
            StockBottleInfoDto(2L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 2, looseQty = 165, packageQty = 100),
            // No loose pills: contributes sealed bottles only, never an open bottle.
            StockBottleInfoDto(3L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 1, looseQty = 0, packageQty = 100),
        )

        val raw = InventoryMessageBuilder.buildInventoryMessage(batch = batch, bottles = bottles)
        val lines = raw.split("\r")

        assertEquals(1, lines.count { it.startsWith("INV|") })
        val sealedRow = lines.first { it.startsWith("OBX|") && it.contains("SEALED_QTY") }
        val openRow = lines.first { it.startsWith("OBX|") && it.contains("OPEN_QTY") }
        assertEquals("400^4", sealedRow.split("|").getOrNull(5))
        assertEquals("365^2", openRow.split("|").getOrNull(5))
    }

    @Test
    fun `buildInventoryMessage totals the shape the capture flow actually writes`() {
        val batch = StockBatchEntity(batchId = 11L, requestIdFromPMS = null, bucketId = "BUCKET-11")
        // The real on-disk shape: persistActive writes sealed-only lines, and each
        // "Scan Pills" session writes its own loose-only line with bottleQty 0.
        val bottles = listOf(
            StockBottleInfoDto(1L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 2, looseQty = 0, packageQty = 1000),
            StockBottleInfoDto(2L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 0, looseQty = 200, packageQty = 1000),
            StockBottleInfoDto(3L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 0, looseQty = 100, packageQty = 1000),
            StockBottleInfoDto(4L, 1L, "DrugA", "NDC-A", "LOTA", "EXPA", bottleQty = 0, looseQty = 65, packageQty = 1000),
        )

        val raw = InventoryMessageBuilder.buildInventoryMessage(batch = batch, bottles = bottles)
        val lines = raw.split("\r")

        assertEquals(1, lines.count { it.startsWith("INV|") })
        val sealedRow = lines.first { it.startsWith("OBX|") && it.contains("SEALED_QTY") }
        val openRow = lines.first { it.startsWith("OBX|") && it.contains("OPEN_QTY") }
        assertEquals("2000^2", sealedRow.split("|").getOrNull(5))
        assertEquals("365^3", openRow.split("|").getOrNull(5))
    }
}
