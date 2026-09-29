package com.dispensesure.retail.core.utils.logger.destination

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.dispensesure.retail.core.utils.common.NetworkUtils
import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogLevel
import com.dispensesure.retail.core.utils.logger.destination.remote.IRemoteLogApi
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Unit tests for [RemoteLogDestination]'s connectivity-aware behaviour: send-when-online,
 * queue-to-file-when-offline, and flush-the-queue-when-connectivity-returns.
 */
class RemoteLogDestinationTest {

    private lateinit var tempDir: File
    private lateinit var pendingFile: File
    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var api: IRemoteLogApi
    private val networkCallbackSlot: CapturingSlot<ConnectivityManager.NetworkCallback> = slot()

    @Before
    fun setup() {
        tempDir = createTempDirectory(prefix = "remote_log_destination_test_").toFile()
        pendingFile = File(tempDir, "pending_remote_logs.jsonl")

        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        connectivityManager = mockk(relaxed = true)
        every { connectivityManager.registerDefaultNetworkCallback(capture(networkCallbackSlot)) } just Runs

        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager

        api = mockk()
        coEvery { api.sendLog(any()) } returns Response.success("".toResponseBody(null))

        mockkObject(NetworkUtils)
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    private fun online(available: Boolean) {
        every { NetworkUtils.isNetworkAvailable(any()) } returns available
    }

    private fun destination() = RemoteLogDestination(context, apiOverride = api, pendingLogFileOverride = pendingFile)

    private fun entry(level: LogLevel = LogLevel.ERROR, message: String = "something failed", throwable: Throwable? = null) =
        LogEntry(
            timestampMillis = 0L,
            level = level,
            fileName = "Foo.kt",
            className = "Foo",
            methodName = "bar",
            message = message,
            throwable = throwable
        )

    @Test
    fun `write ignores non-ERROR entries entirely`() {
        online(true)
        val destination = destination()

        destination.write(entry(level = LogLevel.WARN, message = "should be ignored"))
        destination.awaitIdleForTest()

        coVerify(exactly = 0) { api.sendLog(any()) }
        assertFalse(pendingFile.exists())
    }

    @Test
    fun `write sends an ERROR entry immediately when online`() {
        online(true)
        val destination = destination()

        destination.write(entry(message = "online-marker"))
        destination.awaitIdleForTest()

        coVerify(exactly = 1) { api.sendLog(match { it.message.contains("online-marker") }) }
        assertFalse("Nothing should be queued while online", pendingFile.exists())
    }

    @Test
    fun `write queues to the pending file instead of sending when offline`() {
        online(false)
        val destination = destination()

        destination.write(entry(message = "offline-marker"))
        destination.awaitIdleForTest()

        coVerify(exactly = 0) { api.sendLog(any()) }
        assertTrue(pendingFile.exists())
        assertTrue(pendingFile.readText().contains("offline-marker"))
    }

    @Test
    fun `a non-ERROR entry is not queued while offline`() {
        online(false)
        val destination = destination()

        destination.write(entry(level = LogLevel.WARN, message = "should not be queued"))
        destination.awaitIdleForTest()

        assertFalse(pendingFile.exists())
    }

    @Test
    fun `multiple offline entries all land in the pending file`() {
        online(false)
        val destination = destination()

        destination.write(entry(message = "offline-1"))
        destination.write(entry(message = "offline-2"))
        destination.awaitIdleForTest()

        val content = pendingFile.readText()
        assertTrue(content.contains("offline-1"))
        assertTrue(content.contains("offline-2"))
        assertEquals(2, content.trim().lines().size)
    }

    @Test
    fun `an online send failure is swallowed and not queued for retry`() {
        online(true)
        coEvery { api.sendLog(any()) } throws RuntimeException("boom")
        val destination = destination()

        destination.write(entry(message = "send-failure-marker"))
        destination.awaitIdleForTest()

        assertFalse("Connectivity-only fallback: an actual send failure while online must not be queued", pendingFile.exists())
    }

    @Test
    fun `a persist failure is caught and does not throw`() {
        online(false)
        // A file, not a directory, as the pending file's parent — mkdirs() cannot create it.
        val blockedParent = File(tempDir, "blocked").apply { writeText("not a directory") }
        pendingFile = File(blockedParent, "child_dir_that_cannot_exist/pending_remote_logs.jsonl")
        val destination = destination()

        destination.write(entry(message = "should not crash"))
        destination.awaitIdleForTest()
    }

    @Test
    fun `connectivity being restored flushes the pending queue`() {
        online(false)
        val destination = destination()
        destination.write(entry(message = "queued-marker"))
        destination.awaitIdleForTest()
        assertTrue(pendingFile.readText().contains("queued-marker"))

        online(true)
        networkCallbackSlot.captured.onAvailable(mockk<Network>(relaxed = true))
        destination.awaitIdleForTest()

        coVerify(exactly = 1) { api.sendLog(match { it.message.contains("queued-marker") }) }
        assertFalse("Successfully-flushed entries must be cleared from the pending file", pendingFile.readText().contains("queued-marker"))
    }

    @Test
    fun `a successful online write opportunistically flushes any existing backlog`() {
        online(false)
        val destination = destination()
        destination.write(entry(message = "backlog-marker"))
        destination.awaitIdleForTest()

        online(true)
        destination.write(entry(message = "new-online-marker"))
        destination.awaitIdleForTest()

        coVerify(exactly = 1) { api.sendLog(match { it.message.contains("backlog-marker") }) }
        coVerify(exactly = 1) { api.sendLog(match { it.message.contains("new-online-marker") }) }
        assertFalse(pendingFile.readText().contains("backlog-marker"))
    }

    @Test
    fun `entries that still fail to send during a flush are re-queued, not lost`() {
        online(false)
        val destination = destination()
        destination.write(entry(message = "will-succeed"))
        destination.write(entry(message = "will-keep-failing"))
        destination.awaitIdleForTest()

        online(true)
        coEvery { api.sendLog(match { it.message.contains("will-succeed") }) } returns Response.success("".toResponseBody(null))
        coEvery { api.sendLog(match { it.message.contains("will-keep-failing") }) } throws RuntimeException("still offline-ish")

        networkCallbackSlot.captured.onAvailable(mockk<Network>(relaxed = true))
        destination.awaitIdleForTest()

        val remaining = pendingFile.readText()
        assertFalse("The entry that sent successfully must be removed from the queue", remaining.contains("will-succeed"))
        assertTrue("The entry that still fails must remain queued for the next flush", remaining.contains("will-keep-failing"))
    }
}
