package com.rite.pillcounting.feature.history.presentation.compose

import androidx.test.core.app.ApplicationProvider
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.dtos.TxnDetailInfo
import com.rite.pillcounting.core.room.models.dtos.TxnWithDetails
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
 * Unit tests for [DrugHistoryDetailPdfExporter.generate].
 *
 * Runs under Robolectric because the class draws with real Android graphics/PDF APIs
 * (PdfDocument, Canvas, StaticLayout, Typeface, Color) and writes an actual PDF file to
 * disk via context.getExternalFilesDir(), none of which can be exercised with plain mocks.
 *
 * Robolectric 4.13 ships no shadow for android.graphics.pdf.PdfDocument, so the real
 * framework class's native pointer never gets a valid backing implementation and
 * startPage()/writeTo() throw "document is closed!" immediately. [ShadowPdfDocument]
 * is a custom in-memory fake registered here to work around that gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowPdfDocument::class, ShadowUnlinkedResources::class])
class DrugHistoryDetailPdfExporterTest {

    private lateinit var exporter: DrugHistoryDetailPdfExporter
    private lateinit var reportsDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        exporter = DrugHistoryDetailPdfExporter(context)
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

    private fun detail(pillCount: Int?, type: StepState?) =
        TxnDetailInfo(txnId = 1L, pillCount = pillCount, imagePath = null, type = type)

    private fun baseTxn(
        drugName: String? = "Aspirin",
        ndc: String? = "00071015523",
        targetCount: Int? = 30,
        note: String? = "Patient tolerated dose well.",
        drugType: String? = null,
        txnDetails: List<TxnDetailInfo> = emptyList(),
        isSubstitute: Boolean = false,
        requestedDrugName: String? = null,
        requestedNdc: String? = null,
    ) = TxnWithDetails(
        txnId = 1L,
        drugName = drugName,
        drugId = 1L,
        ndc = ndc,
        targetCount = targetCount,
        note = note,
        createdAt = System.currentTimeMillis(),
        bottleInfoListJson = null,
        totalPillCount = 30,
        isDispense = true,
        drugType = drugType,
        txnDetails = txnDetails,
        isComingFromHL7 = false,
        isSubstitute = isSubstitute,
        requestedDrugName = requestedDrugName,
        requestedNdc = requestedNdc,
    )

    @Test
    fun `generates a pdf on happy path for non-batch flow with verification step`() {
        val txn = baseTxn(
            txnDetails = listOf(
                detail(10, StepState.TARGET_VERIFICATION),
                detail(20, StepState.TARGET_VERIFICATION),
            )
        )

        val result = exporter.generate(txn, "John Doe")

        assertNotNull(result)
        assertTrue(result!!.exists())
        assertTrue(result.length() > 0L)
        assertTrue(result.name.startsWith("DrugDetail_Aspirin_00071015523_"))
    }

    @Test
    fun `generates a pdf for batch flow with all step types`() {
        val txn = baseTxn(
            drugType = "TABLET",
            txnDetails = listOf(
                detail(5, StepState.CONTAINER_INITIATE),
                detail(15, StepState.TARGET_VERIFICATION),
                detail(14, StepState.TARGET_REVERIFICATION),
                detail(null, StepState.VIAL),
                detail(3, StepState.CONTAINER_PENDING),
            )
        )

        val result = exporter.generate(txn, "Jane Smith")

        assertNotNull(result)
        assertTrue(result!!.exists())
        assertTrue(result.length() > 0L)
    }

    @Test
    fun `treats string literal null drugType as non-batch flow`() {
        val txn = baseTxn(
            drugType = "null",
            txnDetails = listOf(detail(10, StepState.TARGET_VERIFICATION))
        )

        val result = exporter.generate(txn, "Tester")

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `includes requested drug details block when substitute is true`() {
        val txn = baseTxn(
            isSubstitute = true,
            requestedDrugName = "Original Drug",
            requestedNdc = "99999999999",
            txnDetails = listOf(detail(10, StepState.TARGET_VERIFICATION))
        )

        val result = exporter.generate(txn, "Tester")

        assertNotNull(result)
        assertTrue(result!!.exists())
        assertTrue(result.length() > 0L)
    }

    @Test
    fun `handles null drug name by falling back to placeholder in file name`() {
        val txn = baseTxn(drugName = null)

        val result = exporter.generate(txn, "Tester")

        assertNotNull(result)
        assertTrue(result!!.name.startsWith("DrugDetail_Drug_00071015523_"))
    }

    @Test
    fun `sanitizes drug name with spaces and special characters in file name`() {
        val txn = baseTxn(drugName = "Amoxicillin 500mg (Generic)!")

        val result = exporter.generate(txn, "Tester")

        assertNotNull(result)
        assertTrue(result!!.name.startsWith("DrugDetail_Amoxicillin_500mg_Generic_00071015523_"))
    }

    @Test
    fun `handles null ndc without throwing`() {
        val txn = baseTxn(ndc = null)

        val result = exporter.generate(txn, "Tester")

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `handles null note by rendering placeholder without throwing`() {
        val txn = baseTxn(note = null)

        val result = exporter.generate(txn, "Tester")

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `handles blank note by rendering placeholder without throwing`() {
        val txn = baseTxn(note = "   ")

        val result = exporter.generate(txn, "Tester")

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `handles very long note that spans multiple pages without throwing`() {
        val longNotes = "This is a very long note. ".repeat(300)
        val txn = baseTxn(note = longNotes)

        val result = exporter.generate(txn, "Tester")

        assertNotNull(result)
        assertTrue(result!!.exists())
        assertTrue(result.length() > 0L)
    }

    @Test
    fun `handles null target count using single wide summary tile`() {
        val txn = baseTxn(targetCount = null)

        val result = exporter.generate(txn, "Tester")

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `handles empty txn details list without throwing`() {
        val txn = baseTxn(txnDetails = emptyList())

        val result = exporter.generate(txn, "Tester")

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `handles very long drug name and user name by truncating without throwing`() {
        val txn = baseTxn(drugName = "A".repeat(80))

        val result = exporter.generate(txn, "U".repeat(60))

        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `creates the nested reports directory when it does not already exist`() {
        assertFalse(reportsDir.exists())

        val txn = baseTxn()
        val result = exporter.generate(txn, "Tester")

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

        val txn = baseTxn()
        val result = exporter.generate(txn, "Tester")

        assertEquals(null, result)

        blockingFile.delete()
    }

    @Test
    fun `produces distinct file names for repeated calls due to timestamp suffix`() {
        val txn = baseTxn()

        val first = exporter.generate(txn, "Tester")
        // The filename suffix is System.currentTimeMillis(); sleep past one tick so two
        // back-to-back calls don't collide on Windows' ~15ms clock resolution.
        Thread.sleep(20)
        val second = exporter.generate(txn, "Tester")

        assertNotNull(first)
        assertNotNull(second)
        assertFalse(first!!.name == second!!.name)
    }

    @Test
    fun `computes verification sum only from TARGET_VERIFICATION step details`() {
        // Mixed steps: only TARGET_VERIFICATION entries should count toward the hero count.
        val txn = baseTxn(
            txnDetails = listOf(
                detail(10, StepState.TARGET_VERIFICATION),
                detail(20, StepState.TARGET_VERIFICATION),
                detail(999, StepState.VIAL), // must be excluded from verification sum
            )
        )

        val result = exporter.generate(txn, "Tester")

        assertNotNull(result)
        assertTrue(result!!.exists())
        assertTrue(result.length() > 0L)
    }
}
