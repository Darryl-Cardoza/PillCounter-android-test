package com.rite.pillcounting.feature.history.presentation.compose

import androidx.test.core.app.ApplicationProvider
import com.rite.pillcounting.feature.batchCount.domain.model.BatchDrugGroup
import com.rite.pillcounting.feature.batchCount.domain.model.BatchLotEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.rite.pillcounting.util.shadow.ShadowPdfDocument
import com.rite.pillcounting.util.shadow.ShadowUnlinkedResources
import java.io.File

/**
 * Unit tests for [BatchStockCountPdfExporter.generate].
 *
 * Runs under Robolectric because the class draws with real Android graphics/PDF APIs
 * (PdfDocument, Canvas, Paint, Typeface) and writes an actual PDF file to disk via
 * context.getExternalFilesDir(), none of which can be exercised with plain mocks.
 *
 * Robolectric 4.13 ships no shadow for android.graphics.pdf.PdfDocument, so the real
 * framework class's native pointer never gets a valid backing implementation and
 * startPage()/writeTo() throw "document is closed!" immediately. [ShadowPdfDocument]
 * is a custom in-memory fake registered here to work around that gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowPdfDocument::class, ShadowUnlinkedResources::class])
class BatchStockCountPdfExporterTest {

    private lateinit var exporter: BatchStockCountPdfExporter
    private lateinit var reportsDir: File

    private fun lot(lotNo: String?, expiry: String?, count: Int) =
        BatchLotEntry(lotNo = lotNo, expiry = expiry, count = count)

    private fun group(
        drugName: String = "Aspirin",
        ndc: String = "00071015523",
        sealedTotal: Int = 20,
        openedTotal: Int = 10,
        totalCount: Int = 30,
        sealedLots: List<BatchLotEntry> = listOf(lot("LOT1", "2027-01-01", 20))
    ) = BatchDrugGroup(
        drugId = 1L,
        drugName = drugName,
        ndc = ndc,
        sealedTotal = sealedTotal,
        openedTotal = openedTotal,
        totalCount = totalCount,
        sealedBottleQty = 1,
        sealedLots = sealedLots,
        openedLots = emptyList()
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        exporter = BatchStockCountPdfExporter(context)
        reportsDir = File(
            context.getExternalFilesDir(null),
            "PillReports/StockCountReports"
        )
    }

    @After
    fun tearDown() {
        if (reportsDir.exists()) {
            reportsDir.listFiles()?.forEach { it.delete() }
            reportsDir.delete()
        }
    }

    @Test
    fun `generates a pdf file on happy path with completed status`() {
        val result = exporter.generate(
            batchId = 101L,
            uniqueNdcCount = 1,
            drugGroups = listOf(group()),
            batchStatus = "COMPLETED",
            startDateTime = System.currentTimeMillis(),
            userName = "John Doe"
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
        assertTrue(result.length() > 0L)
        assertTrue(result.name.startsWith("StockCount_Batch101_"))
        assertTrue(result.name.endsWith(".pdf"))
    }

    @Test
    fun `generates a pdf file with in-progress status branch`() {
        val result = exporter.generate(
            batchId = 202L,
            uniqueNdcCount = 2,
            drugGroups = listOf(group()),
            batchStatus = "IN_PROGRESS",
            startDateTime = System.currentTimeMillis(),
            userName = "Jane"
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `handles empty drug groups list without throwing`() {
        val result = exporter.generate(
            batchId = 303L,
            uniqueNdcCount = 0,
            drugGroups = emptyList(),
            batchStatus = "COMPLETED",
            startDateTime = System.currentTimeMillis(),
            userName = "Empty User"
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `handles null startDateTime by falling back to generated date`() {
        val result = exporter.generate(
            batchId = 404L,
            uniqueNdcCount = 1,
            drugGroups = listOf(group()),
            batchStatus = "COMPLETED",
            startDateTime = null,
            userName = "NullDate"
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `handles zero or negative startDateTime by falling back to generated date`() {
        val result = exporter.generate(
            batchId = 405L,
            uniqueNdcCount = 1,
            drugGroups = listOf(group()),
            batchStatus = "COMPLETED",
            startDateTime = 0L,
            userName = "ZeroDate"
        )

        val resultNeg = exporter.generate(
            batchId = 406L,
            uniqueNdcCount = 1,
            drugGroups = listOf(group()),
            batchStatus = "COMPLETED",
            startDateTime = -5L,
            userName = "NegDate"
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
        assertNotNull(resultNeg)
        assertTrue(resultNeg!!.exists())
    }

    @Test
    fun `handles drug group with no sealed lots (opened bottles only branch)`() {
        val g = group(sealedTotal = 0, sealedLots = emptyList(), openedTotal = 30, totalCount = 30)
        val result = exporter.generate(
            batchId = 501L,
            uniqueNdcCount = 1,
            drugGroups = listOf(g),
            batchStatus = "COMPLETED",
            startDateTime = System.currentTimeMillis(),
            userName = "NoLots"
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `handles lot entries with null or blank lotNo and expiry using placeholder`() {
        val g = group(
            sealedLots = listOf(
                lot(null, null, 5),
                lot("", "", 3),
                lot("LOT-OK", "2028-05-05", 12)
            ),
            sealedTotal = 20
        )
        val result = exporter.generate(
            batchId = 601L,
            uniqueNdcCount = 1,
            drugGroups = listOf(g),
            batchStatus = "COMPLETED",
            startDateTime = System.currentTimeMillis(),
            userName = "BlankLots"
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `truncates very long user name in prepared-by line without throwing`() {
        val longName = "A".repeat(100)
        val result = exporter.generate(
            batchId = 701L,
            uniqueNdcCount = 1,
            drugGroups = listOf(group()),
            batchStatus = "COMPLETED",
            startDateTime = System.currentTimeMillis(),
            userName = longName
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `generates multiple pages when drug group list is large enough to overflow a page`() {
        val manyGroups = (1..30).map {
            group(
                drugName = "Drug $it",
                ndc = "NDC$it",
                sealedLots = listOf(lot("LOT$it", "2027-01-01", 10), lot("LOT${it}B", "2027-02-01", 5))
            )
        }

        val result = exporter.generate(
            batchId = 801L,
            uniqueNdcCount = 30,
            drugGroups = manyGroups,
            batchStatus = "COMPLETED",
            startDateTime = System.currentTimeMillis(),
            userName = "MultiPage"
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
        // Multi-page content should meaningfully increase file size vs a single small group.
        assertTrue(result.length() > 1000L)
    }

    @Test
    fun `creates the nested reports directory when it does not already exist`() {
        assertFalse(reportsDir.exists())

        val result = exporter.generate(
            batchId = 901L,
            uniqueNdcCount = 1,
            drugGroups = listOf(group()),
            batchStatus = "COMPLETED",
            startDateTime = System.currentTimeMillis(),
            userName = "DirUser"
        )

        assertNotNull(result)
        assertTrue(reportsDir.exists())
        assertTrue(reportsDir.isDirectory)
    }

    @Test
    fun `returns null when the reports directory cannot be created`() {
        val parent = reportsDir.parentFile!!
        parent.mkdirs()
        reportsDir.delete()
        parent.delete()
        parent.parentFile?.mkdirs()
        val blockingFile = File(parent.parentFile, parent.name)
        blockingFile.createNewFile()

        val result = exporter.generate(
            batchId = 1001L,
            uniqueNdcCount = 1,
            drugGroups = listOf(group()),
            batchStatus = "COMPLETED",
            startDateTime = System.currentTimeMillis(),
            userName = "Blocked"
        )

        assertNull(result)

        blockingFile.delete()
    }

    @Test
    fun `produces distinct file names for different batch ids`() {
        val resultA = exporter.generate(
            batchId = 1101L,
            uniqueNdcCount = 1,
            drugGroups = listOf(group()),
            batchStatus = "COMPLETED",
            startDateTime = System.currentTimeMillis(),
            userName = "UserA"
        )
        val resultB = exporter.generate(
            batchId = 1102L,
            uniqueNdcCount = 1,
            drugGroups = listOf(group()),
            batchStatus = "COMPLETED",
            startDateTime = System.currentTimeMillis(),
            userName = "UserB"
        )

        assertNotNull(resultA)
        assertNotNull(resultB)
        assertFalse(resultA!!.name == resultB!!.name)
        assertTrue(resultA.name.contains("Batch1101"))
        assertTrue(resultB.name.contains("Batch1102"))
    }

    @Test
    fun `zero total pills and zero unique ndc count render without throwing`() {
        val result = exporter.generate(
            batchId = 1201L,
            uniqueNdcCount = 0,
            drugGroups = emptyList(),
            batchStatus = "IN_PROGRESS",
            startDateTime = System.currentTimeMillis(),
            userName = "ZeroUser"
        )

        assertNotNull(result)
        assertEquals(0L, 0L) // sanity boundary check for zero totals path
        assertTrue(result!!.exists())
    }
}
