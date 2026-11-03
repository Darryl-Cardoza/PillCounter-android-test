package com.dispensesure.retail.feature.auditLog.presentation

import android.content.Context
import com.dispensesure.retail.core.audit.read.AuditLogEntry
import com.dispensesure.retail.core.audit.read.AuditLogReader
import com.dispensesure.retail.core.audit.read.AuditRecordFormatter
import com.dispensesure.retail.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

class AuditLogViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "audit_export_test_${System.nanoTime()}").apply { mkdirs() }
    private val entry = AuditLogEntry.UnreadableFile("audit-logs-1.unreadable")
    private val context = mockk<Context>()
    private val reader = mockk<AuditLogReader>().also { every { it.readAllOldestFirst() } returns listOf(entry) }

    private fun createViewModel() = AuditLogViewModel(context, reader, mainDispatcherRule.testDispatcher)

    @Test
    fun `export writes the formatted log and emits its path`() = runTest(mainDispatcherRule.testDispatcher) {
        every { context.getExternalFilesDir("audit") } returns tempDir
        val viewModel = createViewModel()

        viewModel.export()
        advanceUntilIdle()

        val exportFile = File(tempDir, "audit-logs-decrypted.log")
        assertEquals(exportFile.absolutePath, viewModel.exportResults.first())
        assertEquals(AuditRecordFormatter.format(entry), exportFile.readText())
    }

    @Test
    fun `export emits null when external storage is unavailable`() = runTest(mainDispatcherRule.testDispatcher) {
        every { context.getExternalFilesDir("audit") } returns null
        val viewModel = createViewModel()

        viewModel.export()
        advanceUntilIdle()

        assertNull(viewModel.exportResults.first())
    }
}
