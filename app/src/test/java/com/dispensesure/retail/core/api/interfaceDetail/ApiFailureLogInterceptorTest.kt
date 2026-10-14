package com.dispensesure.retail.core.api.interfaceDetail

import android.util.Log
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogDestination
import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.logger.LogLevel
import com.dispensesure.retail.core.utils.logger.LoggerConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ApiFailureLogInterceptorTest {

    private val errors = mutableListOf<LogEntry>()
    private val warnings = mutableListOf<LogEntry>()
    private val interceptor = ApiFailureLogInterceptor()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.getStackTraceString(any()) } returns "stack trace"
        LoggerConfig.minimumLogLevel = LogLevel.WARN
        AppLogger.setDestinationForTest(object : LogDestination {
            override fun write(entry: LogEntry) {
                if (entry.level == LogLevel.ERROR) errors += entry
                if (entry.level == LogLevel.WARN) warnings += entry
            }
        })
    }

    @After
    fun tearDown() {
        LoggerConfig.minimumLogLevel = LogLevel.ERROR
        AppLogger.setDestinationForTest(null)
        unmockkAll()
    }

    private fun chain(path: String, code: Int? = null, failure: IOException? = null, canceled: Boolean = false): Interceptor.Chain {
        val request = Request.Builder().url("https://api.test$path?secret=1").build()
        return mockk {
            every { request() } returns request
            every { call().isCanceled() } returns canceled
            if (failure != null) {
                every { proceed(any()) } throws failure
            } else {
                every { proceed(any()) } returns Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(code!!).message("m").build()
            }
        }
    }

    @Test
    fun `non-2xx response logs one error with method and path only`() {
        interceptor.intercept(chain("/mobile/users", code = 500))

        val entry = errors.single()
        assertEquals("GET /mobile/users -> HTTP 500", entry.message)
        assertEquals(LogEvent.NETWORK_ERROR, entry.event)
    }

    @Test
    fun `success, 401 and offline 599 are not logged`() {
        listOf(200, 401, 599).forEach { interceptor.intercept(chain("/mobile/users", code = it)) }

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a timeout is logged as a warning and rethrown`() {
        val failure = SocketTimeoutException("timeout")

        assertThrows(SocketTimeoutException::class.java) { interceptor.intercept(chain("/mobile/users", failure = failure)) }

        assertTrue(errors.isEmpty())
        assertEquals(LogEvent.NETWORK_TIMEOUT, warnings.single().event)
    }

    @Test
    fun `4xx responses and offline failures are warnings, not errors`() {
        interceptor.intercept(chain("/mobile/users", code = 404))
        assertThrows(UnknownHostException::class.java) {
            interceptor.intercept(chain("/mobile/users", failure = UnknownHostException("offline")))
        }

        assertTrue(errors.isEmpty())
        assertEquals(2, warnings.size)
    }

    @Test
    fun `health and token refresh failures are not logged`() {
        interceptor.intercept(chain("/health", code = 500))
        interceptor.intercept(chain("/auth/refresh", code = 500))

        assertTrue(errors.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `cancelled calls and the logs endpoint itself are not logged`() {
        assertThrows(IOException::class.java) {
            interceptor.intercept(chain("/mobile/users", failure = IOException("Canceled"), canceled = true))
        }
        interceptor.intercept(chain("/mobile/logs", code = 500))

        assertTrue(errors.isEmpty())
    }
}
