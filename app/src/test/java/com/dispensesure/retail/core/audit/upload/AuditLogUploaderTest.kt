package com.dispensesure.retail.core.audit.upload

import android.content.Context
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.AuditTestData
import com.dispensesure.retail.core.audit.key.DekRegistrar
import com.dispensesure.retail.core.audit.key.DekVersionRecorder
import com.dispensesure.retail.core.audit.model.AuditJson
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import com.dispensesure.retail.core.audit.store.AuditOutbox
import com.dispensesure.retail.core.audit.store.InMemorySegmentSlot
import com.dispensesure.retail.core.audit.store.MutableTestClock
import com.dispensesure.retail.core.audit.store.SegmentId
import com.dispensesure.retail.core.room.models.DekVersionEntity
import com.dispensesure.retail.core.security.DatabaseKeyProvider
import com.dispensesure.retail.core.utils.device.DeviceKeyProvider
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.Collections

class AuditLogUploaderTest {

    private val directory = File(System.getProperty("java.io.tmpdir"), "audit_uploader_test_${System.nanoTime()}")
    private val dispatcher = StandardTestDispatcher()
    private val outbox = AuditOutbox(directory)
    private val fileStore = AuditLogFileStore(
        directory, InMemorySegmentSlot(), outbox, MutableTestClock(Instant.parse("2026-10-21T12:00:00Z"))
    )
    private val api = mockk<IAuditLogApi>()
    private val preferenceHelper = mockk<PreferenceHelper>(relaxed = true).also {
        every { it.isUserLoggedIn() } returns true
        every { it.getAccessToken() } returns "token"
    }
    private val deviceKeyProvider = mockk<DeviceKeyProvider>().also { coEvery { it.getDeviceKey() } returns "dev-1" }
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val sentForms = Collections.synchronizedList(mutableListOf<MultipartBody>())
    private val closedFilesWhenSent = Collections.synchronizedList(mutableListOf<Int>())
    private val registeredDek = DekVersionEntity(
        dekKeyId = "dek-dev-1", version = 3, wrappedDek = byteArrayOf(1), kekKeyId = "kek-1", kekVersion = 1,
        kekAlgorithm = "AES-256-GCM", isActive = true, isRegistered = true, createdAt = 1000L
    )
    private val dekVersionRecorder = mockk<DekVersionRecorder>(relaxed = true)
    private val dekRegistrar = mockk<DekRegistrar>().also { coEvery { it.registerIfNeeded(any()) } returns registeredDek }

    private val uploader = AuditLogUploader(
        mockk<Context>(relaxed = true), outbox, AuditFilePreparer(fileStore, outbox), dekVersionRecorder, dekRegistrar,
        api, preferenceHelper, deviceKeyProvider, Lazy { auditLogger }, dispatcher
    )

    @Before
    fun dek() {
        mockkObject(DatabaseKeyProvider)
        // A fresh copy each call: the uploader wipes it after preparing.
        every { DatabaseKeyProvider.getOrCreatePassphraseWithoutReset(any()) } answers { ByteArray(32) { 5 } }
    }

    @After
    fun tearDown() = unmockkAll()

    private fun closedFile(seq: Long) {
        fileStore.append(AuditJson.encodeRecord(AuditTestData.record(seq)))
        fileStore.closeActiveFile(onlyIfDayEnded = false)
    }

    /** Answers by the `sequence` part, so the parallel order doesn't matter. */
    private fun answerUploads(replyFor: (sequence: String) -> Response<ResponseBody>) {
        coEvery { api.uploadSegment(any(), any()) } answers {
            val form = secondArg<MultipartBody>()
            sentForms += form
            closedFilesWhenSent += fileStore.closedFilesOldestFirst().size
            replyFor(form.textParts().getValue("sequence"))
        }
    }

    // runCurrent, not advanceUntilIdle: a scheduled retry must not fire inside the test.
    private fun runUpload() {
        uploader.requestUpload()
        dispatcher.scheduler.runCurrent()
    }

    private fun sentSequences() = sentForms.map { it.textParts().getValue("sequence") }.toSet()
    private fun outboxSequences() = outbox.segmentsOldestFirst().map { it.id.sequence }

    @Test
    fun `every closed file is prepared before any request`() {
        closedFile(1)
        closedFile(2)
        answerUploads { reply(201, null) }
        runUpload()
        assertEquals(setOf("1", "2"), sentSequences())
        assertTrue(closedFilesWhenSent.all { it == 0 })
        assertTrue(outboxSequences().isEmpty())
    }

    @Test
    fun `the form carries the prepared meta and the registered dek`() {
        closedFile(1)
        answerUploads { reply(503, "storage_write_failed") } // keeps the file so its meta can be read
        runUpload()

        val meta = outbox.segmentsOldestFirst().single().meta
        val parts = sentForms.single().textParts()
        assertEquals(meta.sha256, parts["sha256"])
        assertEquals(meta.chainLastHash, parts["chain_last_hash"])
        assertEquals("dek-dev-1", parts["dek_key_id"])
        assertEquals("3", parts["dek_version"])
        assertEquals(64, parts.getValue("sha256").length)
    }

    @Test
    fun `logged out seals nothing and sends nothing`() {
        every { preferenceHelper.isUserLoggedIn() } returns false
        closedFile(1)
        runUpload()
        coVerify(exactly = 0) { api.uploadSegment(any(), any()) }
        assertEquals(1, fileStore.closedFilesOldestFirst().size)
        assertTrue(outboxSequences().isEmpty())
    }

    @Test
    fun `logged out still records the wrap`() {
        every { preferenceHelper.isUserLoggedIn() } returns false
        runUpload()
        coVerify { dekVersionRecorder.recordCurrentWrap("dev-1") }
        coVerify(exactly = 0) { dekRegistrar.registerIfNeeded(any()) }
    }

    @Test
    fun `an unregistered dek seals nothing`() {
        coEvery { dekRegistrar.registerIfNeeded(any()) } returns null
        closedFile(1)
        runUpload()
        assertEquals(1, fileStore.closedFilesOldestFirst().size)
        assertTrue(outboxSequences().isEmpty())
        coVerify(exactly = 0) { api.uploadSegment(any(), any()) }
    }

    @Test
    fun `files sealed earlier still upload while the dek is unregistered`() {
        coEvery { dekRegistrar.registerIfNeeded(any()) } returns null
        outbox.put(SegmentId(LocalDate.parse("2026-10-20"), 1), byteArrayOf(9), AuditTestData.sampleMeta)
        answerUploads { reply(201, null) }
        runUpload()
        assertTrue(outboxSequences().isEmpty())
    }

    @Test
    fun `the wrap is recorded before registering`() {
        runUpload()
        coVerifyOrder {
            dekVersionRecorder.recordCurrentWrap("dev-1")
            dekRegistrar.registerIfNeeded("dev-1")
        }
    }

    @Test
    fun `duplicate replay also deletes`() {
        closedFile(1)
        answerUploads { reply(200, "duplicate_replay") }
        runUpload()
        assertTrue(outboxSequences().isEmpty())
    }

    @Test
    fun `server busy keeps the files`() {
        closedFile(1)
        closedFile(2)
        answerUploads { reply(503, "storage_write_failed") }
        runUpload()
        assertEquals(listOf(1, 2), outboxSequences())
    }

    @Test
    fun `a network error keeps the file`() {
        closedFile(1)
        coEvery { api.uploadSegment(any(), any()) } throws IOException("offline")
        runUpload()
        assertEquals(listOf(1), outboxSequences())
    }

    @Test
    fun `slot occupied resends the same bytes at the next free sequence`() {
        closedFile(1)
        answerUploads { sequence ->
            if (sequence == "1") reply(409, "slot_occupied", ""","next_free_sequence":4""") else reply(201, null)
        }
        runUpload()
        assertEquals(setOf("1", "4"), sentSequences())
        assertTrue(outboxSequences().isEmpty())
    }

    @Test
    fun `a rejected file moves to failed while the others still go`() {
        closedFile(1)
        closedFile(2)
        answerUploads { sequence -> if (sequence == "1") reply(422, "invalid_field") else reply(201, null) }
        runUpload()
        assertTrue(outboxSequences().isEmpty())
        assertEquals(1, outbox.failedCount())
    }

    @Test
    fun `device not active parks the files`() {
        closedFile(1)
        closedFile(2)
        answerUploads { reply(403, "device_not_active") }
        runUpload()
        assertEquals(listOf(1, 2), outboxSequences())
    }

    @Test
    fun `each drain asks the writer to close an ended day`() {
        runUpload()
        verify { auditLogger.closeFileForUpload(onlyIfDayEnded = true) }
    }

    @Test
    fun `sealing never uses the dek getter that wipes the db`() {
        closedFile(1)
        answerUploads { reply(201, null) }
        runUpload()
        verify(exactly = 0) { DatabaseKeyProvider.getOrCreateDatabasePassphrase(any()) }
    }

    @Test
    fun `a dek that can't be unwrapped keeps the files for a retry`() {
        every { DatabaseKeyProvider.getOrCreatePassphraseWithoutReset(any()) } throws IllegalStateException("keystore")
        closedFile(1)
        answerUploads { reply(201, null) }
        runUpload()
        assertTrue(sentForms.isEmpty())
        assertEquals(1, fileStore.closedFilesOldestFirst().size)
    }
}
