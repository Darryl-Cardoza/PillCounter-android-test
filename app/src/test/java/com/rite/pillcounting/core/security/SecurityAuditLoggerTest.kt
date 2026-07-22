package com.rite.pillcounting.core.security

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for [SecurityAuditLogger] covering the branches reachable on the plain JVM.
 *
 * The real encrypt/decrypt path goes through `KeyStore.getInstance("AndroidKeyStore")`,
 * which is only available on a real device/emulator (Robolectric does not provide a
 * functional AndroidKeyStore security provider). Per the class's documented contract
 * ("Never throws to the caller"), every public function wraps its body in a try/catch
 * that swallows all exceptions, so on the JVM (where AndroidKeyStore is unavailable)
 * the Keystore lookup throws and is silently swallowed. These tests verify that
 * documented never-throws contract and the safe fallback return values.
 */
class SecurityAuditLoggerTest {

    private fun mockContext(): Context {
        val tempDir = File(
            System.getProperty("java.io.tmpdir"),
            "audit_logger_test_${System.nanoTime()}"
        ).apply { mkdirs() }

        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns tempDir
        return context
    }

    @Test
    fun `log does not throw even though AndroidKeyStore is unavailable on the JVM`() {
        val context = mockContext()
        // Should swallow the internal KeyStoreException/NoSuchProviderException.
        SecurityAuditLogger.log(context, AuditEvent(1L, "check", true, "ok"))
    }

    @Test
    fun `logAll does not throw for a non-empty event list`() {
        val context = mockContext()
        val events = listOf(
            AuditEvent(1L, "checkA", true, "ok"),
            AuditEvent(2L, "checkB", false, "failed")
        )
        SecurityAuditLogger.logAll(context, events)
    }

    @Test
    fun `logAll does not throw for an empty event list`() {
        val context = mockContext()
        SecurityAuditLogger.logAll(context, emptyList())
    }

    @Test
    fun `readAll returns an empty list when no log file exists and keystore is unavailable`() {
        val context = mockContext()
        val result = SecurityAuditLogger.readAll(context)
        assertEquals(emptyList<AuditEvent>(), result)
    }

    @Test
    fun `readAll returns empty list rather than throwing when an unreadable log file is present`() {
        val context = mockContext()
        // Write garbage bytes directly (bypassing the real encryption path) to
        // simulate a corrupt/undecryptable log file on disk.
        val logFile = File(context.filesDir, "security_audit.log")
        logFile.writeBytes(ByteArray(20) { it.toByte() })

        val result = SecurityAuditLogger.readAll(context)
        assertEquals(emptyList<AuditEvent>(), result)
    }

    @Test
    fun `log does not throw when the log file already exceeds the rotation threshold`() {
        val context = mockContext()
        val logFile = File(context.filesDir, "security_audit.log")
        // Exceed MAX_LOG_SIZE_BYTES (512 KB) to exercise the rotateIfNeeded branch.
        logFile.writeBytes(ByteArray(512 * 1024 + 1))

        SecurityAuditLogger.log(context, AuditEvent(1L, "check", true, "ok"))

        // Rotation itself succeeds (pure file I/O, no keystore needed); the original
        // file should have been renamed to the rotated backup name.
        assertTrue(File(context.filesDir, "security_audit_prev.log").exists())
    }
}
