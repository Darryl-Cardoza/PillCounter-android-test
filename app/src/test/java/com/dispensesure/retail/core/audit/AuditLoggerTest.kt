package com.dispensesure.retail.core.audit

import com.dispensesure.retail.core.audit.model.AuditJson
import com.dispensesure.retail.core.audit.model.AuditResult
import com.dispensesure.retail.core.audit.model.DeviceIntegrityCheckFailed
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import com.dispensesure.retail.core.audit.store.AuditOutbox
import com.dispensesure.retail.core.audit.store.AuditSequenceCounter
import com.dispensesure.retail.core.audit.store.InMemorySegmentSlot
import com.dispensesure.retail.core.audit.store.MutableTestClock
import com.dispensesure.retail.core.audit.upload.AuditLogUploader
import com.dispensesure.retail.core.faceAuth.data.OperatorName
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.faceAuth.logic.SessionLockController
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TourSection
import com.dispensesure.retail.core.utils.device.DeviceKeyProvider
import com.dispensesure.retail.core.utils.logger.LoggerConfig
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.inject.Provider

class AuditLoggerTest {

    private val directory = File(System.getProperty("java.io.tmpdir"), "audit_logger_test_${System.nanoTime()}")
    private val writerThreadName = "audit-test-writer"
    private val writerDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, writerThreadName).apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val clock = MutableTestClock(Instant.parse("2026-10-21T10:00:00Z"))
    private val segmentSlot = InMemorySegmentSlot()
    private fun newFileStore() = AuditLogFileStore(directory, segmentSlot, AuditOutbox(directory), clock)

    private val appendThreads = Collections.synchronizedList(mutableListOf<Thread>())
    private val fileStore = spyk(newFileStore()).also { store ->
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

    private val uploader = mockk<AuditLogUploader>(relaxed = true)

    private val integrityEvent = DeviceIntegrityCheckFailed(listOf("EMULATOR"), emptyList(), "unknown")

    private fun createLogger(
        sessionLockProvider: Provider<SessionLockController> = Provider { sessionLockController },
        nameProvider: Provider<OperatorNameProvider> = Provider { operatorNameProvider }
    ) = AuditLogger(
        fileStore, sequenceCounter, preferenceHelper, deviceKeyProvider,
        sessionLockProvider, nameProvider, Lazy { uploader }, writerDispatcher
    )

    private fun recordAt(line: String) = AuditJson.decodeRecord(line)

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        error("Timed out waiting for $description")
    }

    private fun writtenLines(): List<String> =
        newFileStore().readableFilesOldestFirst().flatMap { it.readLines() }.filter { it.isNotBlank() }

    private fun awaitLines(count: Int): List<String> {
        awaitUntil("$count audit lines") { writtenLines().size >= count }
        return writtenLines()
    }

    @Test
    fun `lines are plain JSON records`() {
        createLogger().log(integrityEvent)
        val line = awaitLines(1).single()
        assertTrue(line.startsWith("{"))
        assertEquals("DEVICE_INTEGRITY_CHECK_FAILED", recordAt(line).tag)
    }

    @Test
    fun `record carries the common fields, tag, result and data`() {
        createLogger().log(integrityEvent)
        val record = recordAt(awaitLines(1).single())

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
        val record = recordAt(awaitLines(1).single())
        assertEquals(7L, record.operatorProfileId)
        assertEquals("Asha Rao", record.operatorName)
        coVerify { operatorNameProvider.nameFor(7L, 42L) }
    }

    @Test
    fun `a name lookup that fails leaves only the name out`() {
        coEvery { operatorNameProvider.nameFor(any(), any()) } throws IllegalStateException("db closed")
        createLogger().log(integrityEvent)
        val record = recordAt(awaitLines(1).single())
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
        assertEquals("1042", recordAt(awaitLines(1).single()).userId)
    }

    @Test
    fun `logged out at start means records have no user`() {
        every { preferenceHelper.isUserLoggedIn() } returns false
        createLogger().log(integrityEvent)
        val record = recordAt(awaitLines(1).single())
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

        val userIds = awaitLines(3).map { recordAt(it).userId }
        assertEquals(listOf(null, "9", null), userIds)
    }

    @Test
    fun `email is kept only while the user id is unknown`() {
        every { preferenceHelper.isUserLoggedIn() } returns false
        val auditLogger = createLogger()
        auditLogger.onSessionStarted(userId = null, email = "a@b.c")
        auditLogger.log(integrityEvent)
        assertEquals("a@b.c", recordAt(awaitLines(1).single()).email)
    }

    @Test
    fun `records are written in call order with increasing seq`() {
        val auditLogger = createLogger()
        repeat(5) { auditLogger.log(integrityEvent) }
        val seqs = awaitLines(5).map { recordAt(it).seq }
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
        var appendCalls = 0
        every { fileStore.append(any()) } answers {
            appendThreads += Thread.currentThread()
            if (appendCalls++ == 0) throw IOException("disk full") else callOriginal()
        }
        val auditLogger = createLogger()
        auditLogger.log(integrityEvent)
        auditLogger.log(integrityEvent)
        // seq 1 was used by the dropped event, so the gap shows the loss.
        assertEquals(2L, recordAt(awaitLines(1).single()).seq)
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
        val firstRecord = recordAt(awaitLines(1).first())
        assertEquals("[\"EMULATOR\"]", firstRecord.data["failed_checks"].toString())
    }

    @Test
    fun `a new UTC day closes the old file and asks for an upload`() {
        val auditLogger = createLogger()
        auditLogger.log(integrityEvent)
        awaitLines(1)
        clock.instant = Instant.parse("2026-10-22T00:00:01Z")
        auditLogger.log(integrityEvent)
        awaitLines(2)
        verify(timeout = 3_000) { uploader.requestUpload() }
        assertEquals(1, newFileStore().closedFilesOldestFirst().size)
    }

    @Test
    fun `closing for upload moves the writer on and asks for an upload`() {
        val auditLogger = createLogger()
        auditLogger.log(integrityEvent)
        awaitLines(1)
        auditLogger.closeFileForUpload(onlyIfDayEnded = false)
        verify(timeout = 3_000) { uploader.requestUpload() }
        assertEquals(1, newFileStore().closedFilesOldestFirst().size)
    }

    @Test
    fun `an operator id that cannot be read is left out`() {
        createLogger(sessionLockProvider = Provider { error("not ready") }).log(integrityEvent)
        assertEquals(null, recordAt(awaitLines(1).single()).operatorProfileId)
    }
}
