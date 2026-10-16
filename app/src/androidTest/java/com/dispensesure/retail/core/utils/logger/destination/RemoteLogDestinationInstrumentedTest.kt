package com.dispensesure.retail.core.utils.logger.destination

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogLevel
import com.dispensesure.retail.core.utils.logger.destination.dto.RemoteLogRequest
import com.dispensesure.retail.core.utils.logger.destination.remote.IRemoteLogApi
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import retrofit2.Response
import java.io.File

/**
 * Runs on a physical device/emulator against the real [RemoteLogDestination] (real file I/O,
 * real [Context]) with only its network API and connectivity check faked — proves that an ERROR
 * log written while offline is stored to disk instead of dropped, and that the on-disk backlog
 * is uploaded once connectivity comes back.
 */
@RunWith(AndroidJUnit4::class)
class RemoteLogDestinationInstrumentedTest {

    private lateinit var pendingFile: File
    private lateinit var api: IRemoteLogApi
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var deviceContext: Context
    private var networkAvailable = false
    private var registeredCallback: ConnectivityManager.NetworkCallback? = null

    @Before
    fun setup() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext
        pendingFile = File(realContext.cacheDir, "instrumented_pending_remote_logs_${System.currentTimeMillis()}.jsonl")

        connectivityManager = mock()
        doAnswer { invocation ->
            registeredCallback = invocation.getArgument(0)
            null
        }.whenever(connectivityManager).registerDefaultNetworkCallback(any())

        api = mock()
        runBlocking {
            whenever(api.sendLog(any())).thenReturn(Response.success("".toResponseBody(null)))
        }

        // RemoteLogDestination resolves everything (Logs dir, ConnectivityManager) off
        // `context.applicationContext`, so the fake connectivity service must be reachable
        // through that same call.
        deviceContext = object : ContextWrapper(realContext) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) connectivityManager else super.getSystemService(name)
        }
    }

    @After
    fun tearDown() {
        pendingFile.delete()
    }

    private fun destination() = RemoteLogDestination(
        context = deviceContext,
        apiOverride = api,
        pendingLogFileOverride = pendingFile,
        isNetworkAvailable = { networkAvailable }
    )

    private fun errorEntry(marker: String) = LogEntry(
        timestampMillis = System.currentTimeMillis(),
        level = LogLevel.ERROR,
        fileName = "Foo.kt",
        className = "Foo",
        methodName = "bar",
        message = marker
    )

    @Test
    fun anErrorLoggedWhileOfflineIsStoredToDiskInsteadOfUploaded() {
        networkAvailable = false
        val destination = destination()
        val marker = "offline-marker-${System.currentTimeMillis()}"

        destination.write(errorEntry(marker))
        destination.awaitIdleForTest()

        assertTrue("Offline entry must be persisted to the pending file", pendingFile.exists())
        assertTrue(pendingFile.readText().contains(marker))
        verifyNoInteractions(api)
    }

    @Test
    fun theOnDiskBacklogIsUploadedAndClearedOnceConnectivityReturns() {
        networkAvailable = false
        val destination = destination()
        val marker = "backlog-marker-${System.currentTimeMillis()}"

        destination.write(errorEntry(marker))
        destination.awaitIdleForTest()
        assertTrue("Precondition: entry must have been queued while offline", pendingFile.readText().contains(marker))

        networkAvailable = true
        val callback = registeredCallback ?: error("RemoteLogDestination never registered a connectivity callback")
        callback.onAvailable(mock<Network>())
        destination.awaitIdleForTest()

        val captor = argumentCaptor<RemoteLogRequest>()
        verifyBlocking(api, times(1)) { sendLog(captor.capture()) }
        assertTrue("The uploaded request must be the queued entry", captor.firstValue.message.contains(marker))
        assertFalse("Backlog entry must be cleared from disk after a successful upload", pendingFile.readText().contains(marker))
    }
}
