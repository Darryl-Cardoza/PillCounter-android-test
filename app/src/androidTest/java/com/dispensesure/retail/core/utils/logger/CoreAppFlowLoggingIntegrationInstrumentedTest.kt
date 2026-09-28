package com.dispensesure.retail.core.utils.logger

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dispensesure.retail.core.utils.common.PDFHelperExporter
import com.dispensesure.retail.core.utils.logger.destination.FileLogDestination
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a physical device/emulator. Proves logging stays observational (spec rule 14): a real
 * business flow ([PDFHelperExporter]) must keep working even while its own logging fails or is slow.
 */
@RunWith(AndroidJUnit4::class)
class CoreAppFlowLoggingIntegrationInstrumentedTest {

    private lateinit var appContext: Context
    private lateinit var realDestination: FileLogDestination

    @Before
    fun setup() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        AppLogger.init(appContext)
        realDestination = AppLogger.currentDestination() as? FileLogDestination
            ?: FileLogDestination(appContext).also { AppLogger.setDestinationForTest(it) }
    }

    @After
    fun tearDown() {
        // Restore the real destination so later tests/classes in the same instrumentation run
        // aren't left pointed at the broken one this test deliberately installs.
        AppLogger.setDestinationForTest(realDestination)
    }

    @Test
    fun pdfExportStillSucceedsWhenTheLogDestinationCannotWriteAtAll() {
        val blockedPath = File(appContext.cacheDir, "blocked_external_files_dir_${System.currentTimeMillis()}")
            .apply { writeText("not a directory") }
        AppLogger.setDestinationForTest(FileLogDestination(BlockedExternalFilesDirContext(appContext, blockedPath)))

        val exporter = PDFHelperExporter(appContext)
        val file = exporter.generateDrugHistoryPdf(
            drugName = "Test Drug",
            totalCount = "42",
            notes = "Deliberate logger-failure scenario",
            ndc = "0000-0000-00",
            expiry = "2027-01-01",
            lotNo = "LOT123",
            date = "2026-09-28",
            time = "10:00"
        )

        assertNotNull("PDF export must still return a file even though its log write fails", file)
        assertTrue("The PDF must actually be written to disk", file!!.exists() && file.length() > 0)
    }

    @Test
    fun burstOfErrorLogsFromACoreFlowReturnsQuicklyWithoutBlockingOnDiskIo() {
        val logger = AppLogger("CoreAppFlowLoggingIntegrationTest")

        val elapsedMillis = measureMillis {
            repeat(50) { i -> logger.e("Simulated core-flow failure $i", RuntimeException("boom")) }
        }

        assertTrue(
            "50 error logs took ${elapsedMillis}ms — writes must be dispatched off the calling " +
                "thread so a real UI/business flow logging an error is never blocked on disk I/O",
            elapsedMillis < 500
        )
    }

    private inline fun measureMillis(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }

    /**
     * Redirects [getExternalFilesDir] to a path that is a file, not a directory, so any
     * destination built from it can never create its log directory. [FileLogDestination] resolves
     * paths off `context.applicationContext`, so that must also route back through this wrapper.
     */
    private class BlockedExternalFilesDirContext(base: Context, private val blockedPath: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = blockedPath
    }
}
