package com.dispensesure.retail.feature.auditLog.presentation

import android.content.Context
import com.dispensesure.retail.core.audit.DeviceIntegrityAuditor
import com.dispensesure.retail.core.audit.read.AuditLogEntry
import com.dispensesure.retail.core.audit.read.AuditLogReader
import com.dispensesure.retail.core.audit.read.AuditRecordFormatter
import com.dispensesure.retail.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val deviceIntegrityAuditor = mockk<DeviceIntegrityAuditor>()

    private fun createViewModel() =
        AuditLogViewModel(context, reader, deviceIntegrityAuditor, mainDispatcherRule.testDispatcher)

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

    @Test
    fun `run integrity check emits true when the event was logged`() = runTest(mainDispatcherRule.testDispatcher) {
        every { deviceIntegrityAuditor.logFailedIntegrityChecks() } returns true
        val viewModel = createViewModel()

        viewModel.runIntegrityCheck()
        advanceUntilIdle()

        assertTrue(viewModel.integrityCheckResults.first())
        verify(exactly = 1) { deviceIntegrityAuditor.logFailedIntegrityChecks() }
    }

    @Test
    fun `run integrity check emits false when every check passed`() = runTest(mainDispatcherRule.testDispatcher) {
        every { deviceIntegrityAuditor.logFailedIntegrityChecks() } returns false
        val viewModel = createViewModel()

        viewModel.runIntegrityCheck()
        advanceUntilIdle()

        assertFalse(viewModel.integrityCheckResults.first())
    }
}
