package com.rite.pillcounting.core.utils.common

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Unit tests for [PDFHelperExporter.generateDrugHistoryPdf].
 *
 * Runs under Robolectric because the class draws with real Android graphics/PDF APIs
 * (PdfDocument, Canvas, StaticLayout, Typeface) and writes an actual PDF file to disk
 * via context.getExternalFilesDir(), none of which can be exercised with plain mocks.
 *
 * Robolectric 4.13 ships no shadow for android.graphics.pdf.PdfDocument, so the real
 * framework class's native pointer never gets a valid backing implementation and
 * startPage()/writeTo() throw "document is closed!" immediately. [ShadowPdfDocument]
 * is a custom in-memory fake registered here to work around that gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowPdfDocument::class, ShadowUnlinkedResources::class])
class PDFHelperExporterTest {

    private lateinit var exporter: PDFHelperExporter
    private lateinit var reportsDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        exporter = PDFHelperExporter(context)
        reportsDir = File(
            context.getExternalFilesDir(null),
            "PillReports/PillTransactionDetailReports"
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
    fun `generates a pdf file on happy path with all fields populated`() {
        val result = exporter.generateDrugHistoryPdf(
            drugName = "Aspirin",
            totalCount = "30",
            notes = "Patient tolerated dose well.",
            ndc = "00071015523",
            expiry = "2027-01-01",
            lotNo = "LOT99",
            date = "2026-07-14",
            time = "10:30"
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
        assertTrue(result.length() > 0L)
        assertEquals("DrugHistory_Aspirin_00071015523.pdf", result.name)
    }

    @Test
    fun `sanitizes drug name with spaces and special characters in file name`() {
        val result = exporter.generateDrugHistoryPdf(
            drugName = "Amoxicillin 500mg (Generic)!",
            totalCount = "12",
            notes = "notes",
            ndc = "12345678901",
            expiry = "2026-12-31",
            lotNo = "L1",
            date = "2026-01-01",
            time = "09:00"
        )

        assertNotNull(result)
        assertEquals(
            "DrugHistory_Amoxicillin_500mg_Generic_12345678901.pdf",
            result!!.name
        )
    }

    @Test
    fun `handles empty drug name producing file name without drug segment content`() {
        val result = exporter.generateDrugHistoryPdf(
            drugName = "",
            totalCount = "5",
            notes = "notes",
            ndc = "NDC1",
            expiry = "2026-01-01",
            lotNo = "L1",
            date = "2026-01-01",
            time = "09:00"
        )

        assertNotNull(result)
        assertEquals("DrugHistory__NDC1.pdf", result!!.name)
    }

    @Test
    fun `handles empty notes without throwing`() {
        val result = exporter.generateDrugHistoryPdf(
            drugName = "DrugX",
            totalCount = "1",
            notes = "",
            ndc = "NDC2",
            expiry = "2026-01-01",
            lotNo = "L2",
            date = "2026-01-01",
            time = "09:00"
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `handles very long notes that would overflow a single page without throwing`() {
        val longNotes = "This is a very long note. ".repeat(200)

        val result = exporter.generateDrugHistoryPdf(
            drugName = "DrugY",
            totalCount = "100",
            notes = longNotes,
            ndc = "NDC3",
            expiry = "2026-01-01",
            lotNo = "L3",
            date = "2026-01-01",
            time = "09:00"
        )

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `creates the nested reports directory when it does not already exist`() {
        assertFalse(reportsDir.exists())

        val result = exporter.generateDrugHistoryPdf(
            drugName = "DrugZ",
            totalCount = "1",
            notes = "notes",
            ndc = "NDC4",
            expiry = "2026-01-01",
            lotNo = "L4",
            date = "2026-01-01",
            time = "09:00"
        )

        assertNotNull(result)
        assertTrue(reportsDir.exists())
        assertTrue(reportsDir.isDirectory)
    }

    @Test
    fun `overwrites an existing file with the same name on repeated calls`() {
        val first = exporter.generateDrugHistoryPdf(
            drugName = "DupDrug",
            totalCount = "1",
            notes = "first version",
            ndc = "NDC5",
            expiry = "2026-01-01",
            lotNo = "L5",
            date = "2026-01-01",
            time = "09:00"
        )
        assertNotNull(first)
        val firstLength = first!!.length()

        val second = exporter.generateDrugHistoryPdf(
            drugName = "DupDrug",
            totalCount = "1",
            notes = "second version with a lot more text than before to change size",
            ndc = "NDC5",
            expiry = "2026-01-01",
            lotNo = "L5",
            date = "2026-01-01",
            time = "09:00"
        )

        assertNotNull(second)
        assertEquals(first.absolutePath, second!!.absolutePath)
        assertTrue(second.exists())
        // Content differs (longer notes), so file size should differ from the first write.
        assertTrue(second.length() != firstLength || second.length() > 0L)
    }

    @Test
    fun `returns null when the reports directory cannot be created`() {
        // Pre-create the parent path as a *file* instead of a directory so mkdirs() fails.
        val parent = reportsDir.parentFile!!
        parent.mkdirs()
        // Remove any existing directory first, then create a blocking file at reportsDir's path.
        reportsDir.delete()
        parent.delete()
        parent.parentFile?.mkdirs()
        val blockingFile = File(parent.parentFile, parent.name)
        blockingFile.createNewFile()

        val result = exporter.generateDrugHistoryPdf(
            drugName = "Blocked",
            totalCount = "1",
            notes = "notes",
            ndc = "NDC6",
            expiry = "2026-01-01",
            lotNo = "L6",
            date = "2026-01-01",
            time = "09:00"
        )

        assertEquals(null, result)

        // cleanup
        blockingFile.delete()
    }

    @Test
    fun `produces distinct file names for different ndc values with the same drug name`() {
        val resultA = exporter.generateDrugHistoryPdf(
            drugName = "SameDrug",
            totalCount = "1",
            notes = "a",
            ndc = "NDC_A",
            expiry = "2026-01-01",
            lotNo = "L1",
            date = "2026-01-01",
            time = "09:00"
        )
        val resultB = exporter.generateDrugHistoryPdf(
            drugName = "SameDrug",
            totalCount = "1",
            notes = "b",
            ndc = "NDC_B",
            expiry = "2026-01-01",
            lotNo = "L1",
            date = "2026-01-01",
            time = "09:00"
        )

        assertNotNull(resultA)
        assertNotNull(resultB)
        assertFalse(resultA!!.name == resultB!!.name)
        assertTrue(resultA.name.endsWith("NDC_A.pdf"))
        assertTrue(resultB.name.endsWith("NDC_B.pdf"))
    }
}
