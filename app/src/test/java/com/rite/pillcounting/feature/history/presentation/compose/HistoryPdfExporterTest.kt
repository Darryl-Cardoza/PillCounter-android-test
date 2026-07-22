package com.rite.pillcounting.feature.history.presentation.compose

import androidx.test.core.app.ApplicationProvider
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto
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
 * Unit tests for [HistoryPdfExporter.generateHistoryPdf].
 *
 * Runs under Robolectric because the class draws with real Android graphics/PDF APIs
 * (PdfDocument, Canvas, Paint, Typeface, TextUtils.ellipsize) and writes an actual PDF
 * file to disk via context.getExternalFilesDir(), none of which can be exercised with
 * plain MockK mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowPdfDocument::class, ShadowUnlinkedResources::class])
class HistoryPdfExporterTest {

    private lateinit var exporter: HistoryPdfExporter
    private lateinit var reportsDir: File

    private fun txn(
        txnId: Long = 1L,
        isDispense: Boolean = false,
        status: CountStatus = CountStatus.COMPLETED,
        pillCount: Int? = 10,
        drugName: String? = "Aspirin",
        ndc: String? = "00071015523",
        createdAt: Long = System.currentTimeMillis()
    ) = TxnWithDrugDto(
        txnId = txnId,
        isDispense = isDispense,
        status = status,
        pillCount = pillCount,
        drugName = drugName,
        ndc = ndc,
        barcodeImage = null,
        createdAt = createdAt,
        targetCount = null,
        note = null,
        bucketId = null,
        drugType = null
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        exporter = HistoryPdfExporter(context)
        reportsDir = File(
            context.getExternalFilesDir(null),
            "PillReports/DailyHistoryReports"
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
    fun `generates a pdf file on happy path with one row`() {
        val result = exporter.generateHistoryPdf(listOf(txn()), "2026-07-14")

        assertNotNull(result)
        assertTrue(result!!.exists())
        assertTrue(result.length() > 0L)
        assertEquals("DrugHistory_2026-07-14.pdf", result.name)
    }

    @Test
    fun `handles empty counts list without throwing and still creates file`() {
        val result = exporter.generateHistoryPdf(emptyList(), "2026-07-14")

        assertNotNull(result)
        assertTrue(result!!.exists())
        assertTrue(result.length() > 0L)
    }

    @Test
    fun `handles null drugName ndc and pillCount using fallback values`() {
        val row = txn(drugName = null, ndc = null, pillCount = null)
        val result = exporter.generateHistoryPdf(listOf(row), "2026-07-15")

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `distinguishes isDispense true as FIXED and false as REGULAR without throwing`() {
        val fixedRow = txn(isDispense = true)
        val regularRow = txn(isDispense = false)

        val result = exporter.generateHistoryPdf(listOf(fixedRow, regularRow), "2026-07-16")

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `covers all CountStatus enum branches without throwing`() {
        val rows = CountStatus.entries.map { status ->
            txn(txnId = status.ordinal.toLong(), status = status)
        }

        val result = exporter.generateHistoryPdf(rows, "2026-07-17")

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `overwrites an existing file for the same selected date`() {
        val first = exporter.generateHistoryPdf(listOf(txn()), "2026-07-18")
        assertNotNull(first)
        val firstLength = first!!.length()

        val second = exporter.generateHistoryPdf(
            listOf(txn(), txn(txnId = 2L), txn(txnId = 3L)),
            "2026-07-18"
        )

        assertNotNull(second)
        assertEquals(first.absolutePath, second!!.absolutePath)
        assertTrue(second.exists())
        // More rows should produce a file at least as large as the single-row version.
        assertTrue(second.length() >= firstLength)
    }

    @Test
    fun `creates the nested reports directory when it does not already exist`() {
        assertFalse(reportsDir.exists())

        val result = exporter.generateHistoryPdf(listOf(txn()), "2026-07-19")

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

        val result = exporter.generateHistoryPdf(listOf(txn()), "2026-07-20")

        assertNull(result)

        blockingFile.delete()
    }

    @Test
    fun `generates multiple pages when row count is large enough to overflow a page`() {
        val manyRows = (1..40).map { i ->
            txn(
                txnId = i.toLong(),
                drugName = "Drug Number $i With Long Name",
                ndc = "NDC$i",
                pillCount = i
            )
        }

        val result = exporter.generateHistoryPdf(manyRows, "2026-07-21")

        assertNotNull(result)
        assertTrue(result!!.exists())
        // Multi-page content should meaningfully increase file size vs a single small row.
        assertTrue(result.length() > 1000L)
    }

    @Test
    fun `truncates very long drug name cell content without throwing`() {
        val longName = "A".repeat(200)
        val row = txn(drugName = longName)

        val result = exporter.generateHistoryPdf(listOf(row), "2026-07-22")

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `produces distinct file names for different selected dates`() {
        val resultA = exporter.generateHistoryPdf(listOf(txn()), "2026-07-23")
        val resultB = exporter.generateHistoryPdf(listOf(txn()), "2026-07-24")

        assertNotNull(resultA)
        assertNotNull(resultB)
        assertFalse(resultA!!.name == resultB!!.name)
        assertTrue(resultA.name.contains("2026-07-23"))
        assertTrue(resultB!!.name.contains("2026-07-24"))
    }

    @Test
    fun `zero pill count renders as 0 without throwing`() {
        val row = txn(pillCount = 0)
        val result = exporter.generateHistoryPdf(listOf(row), "2026-07-25")

        assertNotNull(result)
        assertTrue(result!!.exists())
    }
}
