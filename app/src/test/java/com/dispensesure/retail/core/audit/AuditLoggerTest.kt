package com.dispensesure.retail.core.audit

import com.dispensesure.retail.core.audit.crypto.AuditRecordCipher
import com.dispensesure.retail.core.audit.model.AuditJson
import com.dispensesure.retail.core.audit.model.AuditResult
import com.dispensesure.retail.core.audit.model.DeviceIntegrityCheckFailed
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import com.dispensesure.retail.core.audit.store.AuditSequenceCounter
import com.dispensesure.retail.core.faceAuth.data.OperatorName
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.faceAuth.logic.SessionLockController
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TourSection
import com.dispensesure.retail.core.utils.device.DeviceKeyProvider
import com.dispensesure.retail.core.utils.logger.LoggerConfig
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.crypto.spec.SecretKeySpec
import javax.inject.Provider

class AuditLoggerTest {

    private val directory = File(System.getProperty("java.io.tmpdir"), "audit_logger_test_${System.nanoTime()}")
    private val testKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val cipher = AuditRecordCipher { testKey }
    private val writerThreadName = "audit-test-writer"
    private val writerDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, writerThreadName).apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val appendThreads = Collections.synchronizedList(mutableListOf<Thread>())
    private val fileStore = spyk(AuditLogFileStore(directory)).also { store ->
        every { store.append(any()) } answers {
            appendThreads += Thread.currentThread()
            callOriginal()
        }
    }

    private val preferenceHelper = mockk<PreferenceHelper>(relaxed = true).also {
        every { it.isUserLoggedIn() } returns true
        every { it.getUserId() } returns "1042"
        every { it.getSelectedTerminalId() } returns "T-03"
        every { it.getLocalId() } returns 42L
    }
    private val deviceKeyProvider = mockk<DeviceKeyProvider>().also { coEvery { it.getDeviceKey() } returns "dev-1" }
    private val sessionLockController = mockk<SessionLockController>().also {
        every { it.operatorProfileId } returns MutableStateFlow(7L)
    }
    private val operatorNameProvider = mockk<OperatorNameProvider>().also {
        coEvery { it.nameFor(7L, 42L) } returns OperatorName("Asha", "Rao")
    }
    private var nextSeq = 0L
    private val sequenceCounter = mockk<AuditSequenceCounter>().also { every { it.next() } answers { ++nextSeq } }

    private val integrityEvent = DeviceIntegrityCheckFailed(listOf("EMULATOR"), emptyList(), "unknown")

    private fun createLogger(
        recordCipher: AuditRecordCipher = cipher,
        sessionLockProvider: Provider<SessionLockController> = Provider { sessionLockController },
        nameProvider: Provider<OperatorNameProvider> = Provider { operatorNameProvider }
    ) = AuditLogger(
        fileStore, recordCipher, sequenceCounter, preferenceHelper, deviceKeyProvider,
        sessionLockProvider, nameProvider, writerDispatcher
    )

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        error("Timed out waiting for $description")
    }

    private fun currentFileLines(): List<String> {
        val file = File(directory, "audit-logs.log")
        return if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
    }

    private fun awaitLines(count: Int): List<String> {
        awaitUntil("$count audit lines") { currentFileLines().size >= count }
        return currentFileLines()
    }

    @Test
    fun `record carries the common fields, tag, result and data`() {
        createLogger().log(integrityEvent)
        val record = AuditJson.decodeRecord(cipher.decrypt(awaitLines(1).single()))

        assertEquals("DEVICE_INTEGRITY_CHECK_FAILED", record.tag)
        assertEquals(AuditResult.FAILURE, record.result)
        assertEquals(1L, record.seq)
        assertEquals(LoggerConfig.sessionId, record.sessionId)
        assertEquals("1042", record.userId)
        assertEquals(null, record.email)
        assertEquals(7L, record.operatorProfileId)
        assertEquals("dev-1", record.deviceKey)
        assertEquals("T-03", record.terminalId)
        assertTrue(record.data.containsKey("failed_checks"))
    }

    @Test
    fun `operator name comes from the same snapshot as the operator id`() {
        createLogger().log(integrityEvent)
        val record = AuditJson.decodeRecord(cipher.decrypt(awaitLines(1).single()))
        assertEquals(7L, record.operatorProfileId)
        assertEquals("Asha Rao", record.operatorName)
        coVerify { operatorNameProvider.nameFor(7L, 42L) }
    }

    @Test
    fun `a name lookup that fails leaves only the name out`() {
        coEvery { operatorNameProvider.nameFor(any(), any()) } throws IllegalStateException("db closed")
        createLogger().log(integrityEvent)
        val record = AuditJson.decodeRecord(cipher.decrypt(awaitLines(1).single()))
        assertEquals(7L, record.operatorProfileId)
        assertEquals(null, record.operatorName)
    }

    @Test
    fun `the user logged in at construction owns the first record even if logged out right after`() {
        // Hold the writer thread so the logout lands before the writer could read the prefs.
        val writerHeld = CountDownLatch(1)
        writerDispatcher.executor.execute { writerHeld.await() }
        val auditLogger = createLogger()
        every { preferenceHelper.isUserLoggedIn() } returns false
        writerHeld.countDown()
        auditLogger.log(integrityEvent)
        assertEquals("1042", AuditJson.decodeRecord(cipher.decrypt(awaitLines(1).single())).userId)
    }

    @Test
    fun `logged out at start means records have no user`() {
        every { preferenceHelper.isUserLoggedIn() } returns false
        createLogger().log(integrityEvent)
        val record = AuditJson.decodeRecord(cipher.decrypt(awaitLines(1).single()))
        assertEquals(null, record.userId)
        assertEquals(null, record.email)
    }

    @Test
    fun `session markers switch the user in call order`() {
        every { preferenceHelper.isUserLoggedIn() } returns false
        val auditLogger = createLogger()
        auditLogger.log(integrityEvent)
        auditLogger.onSessionStarted(userId = "9", email = "a@b.c")
        auditLogger.log(integrityEvent)
        auditLogger.onSessionEnded()
        auditLogger.log(integrityEvent)

        val userIds = awaitLines(3).map { AuditJson.decodeRecord(cipher.decrypt(it)).userId }
        assertEquals(listOf(null, "9", null), userIds)
    }

    @Test
    fun `email is kept only while the user id is unknown`() {
        every { preferenceHelper.isUserLoggedIn() } returns false
        val auditLogger = createLogger()
        auditLogger.onSessionStarted(userId = null, email = "a@b.c")
        auditLogger.log(integrityEvent)
        assertEquals("a@b.c", AuditJson.decodeRecord(cipher.decrypt(awaitLines(1).single())).email)
    }

    @Test
    fun `records are written in call order with increasing seq`() {
        val auditLogger = createLogger()
        repeat(5) { auditLogger.log(integrityEvent) }
        val seqs = awaitLines(5).map { AuditJson.decodeRecord(cipher.decrypt(it)).seq }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), seqs)
    }

    @Test
    fun `writes never happen on the calling thread`() {
        createLogger().log(integrityEvent)
        awaitLines(1)
        // Compare threads, not names: coroutine debug mode adds " @coroutine#N" to the name.
        val appendThread = appendThreads.single()
        assertTrue(appendThread !== Thread.currentThread())
        assertTrue(appendThread.name.startsWith(writerThreadName))
    }

    @Test
    fun `a failed write is dropped and the next event still writes`() {
        var keyCalls = 0
        val flakyCipher = AuditRecordCipher { if (keyCalls++ == 0) error("key unavailable") else testKey }
        val auditLogger = createLogger(flakyCipher)
        auditLogger.log(integrityEvent)
        auditLogger.log(integrityEvent)
        // seq 1 was used by the dropped event, so the gap shows the loss.
        assertEquals(2L, AuditJson.decodeRecord(cipher.decrypt(awaitLines(1).single())).seq)
    }

    @Test
    fun `logOutsideTour drops events while the tour runs`() {
        val auditLogger = createLogger()
        val tourEvent = DeviceIntegrityCheckFailed(listOf("TOUR"), emptyList(), "unknown")
        FtueController.start(listOf(TourSection.DISPENSE), hl7Enabled = false, notesEnabled = false, bucketsEnabled = true)
        try {
            auditLogger.logOutsideTour(tourEvent)
        } finally {
            FtueController.end() // process-wide singleton: don't leak the tour into other tests
        }
        auditLogger.logOutsideTour(integrityEvent)

        // Records are written in call order, so a kept tour event would be the first line.
        val firstRecord = AuditJson.decodeRecord(cipher.decrypt(awaitLines(1).first()))
        assertEquals("[\"EMULATOR\"]", firstRecord.data["failed_checks"].toString())
    }

    @Test
    fun `files from an older key are moved aside before the first write`() {
        val oldCipher = AuditRecordCipher { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        AuditLogFileStore(directory).append(oldCipher.encrypt("{}"))

        createLogger().log(integrityEvent)

        // The old line sits in the current file until the worker moves it, so wait for the move.
        awaitUntil("old file moved aside") { AuditLogFileStore(directory).unreadableFiles().size == 1 }
        val newRecord = AuditJson.decodeRecord(cipher.decrypt(awaitLines(1).single()))
        assertEquals("DEVICE_INTEGRITY_CHECK_FAILED", newRecord.tag)
    }

    @Test
    fun `a cut-off current line with readable rotated files moves nothing aside`() {
        directory.mkdirs()
        File(directory, "audit-logs-1.log").writeText(cipher.encrypt("{}") + "\n")
        // Killed mid-write: no newline at the end.
        File(directory, "audit-logs.log").writeText(cipher.encrypt("{}").dropLast(10))

        createLogger().log(integrityEvent)

        awaitLines(2)
        assertTrue(AuditLogFileStore(directory).unreadableFiles().isEmpty())
        assertTrue(File(directory, "audit-logs-1.log").exists())
    }

    @Test
    fun `a cut-off only line still lets the next record write`() {
        directory.mkdirs()
        File(directory, "audit-logs.log").writeText(cipher.encrypt("{}").dropLast(10))

        createLogger().log(integrityEvent)

        awaitUntil("new record") { currentFileLines().any { runCatching { cipher.decrypt(it) }.isSuccess } }
    }

    @Test
    fun `a key that fails to load moves nothing aside`() {
        AuditLogFileStore(directory).append(cipher.encrypt("{}"))
        var keyCalls = 0
        val flakyCipher = AuditRecordCipher { if (keyCalls++ == 0) error("keystore") else testKey }

        createLogger(flakyCipher).log(integrityEvent)

        awaitLines(2)
        assertTrue(AuditLogFileStore(directory).unreadableFiles().isEmpty())
    }

    @Test
    fun `an operator id that cannot be read is left out`() {
        createLogger(sessionLockProvider = Provider { error("not ready") }).log(integrityEvent)
        assertEquals(null, AuditJson.decodeRecord(cipher.decrypt(awaitLines(1).single())).operatorProfileId)
    }
}
