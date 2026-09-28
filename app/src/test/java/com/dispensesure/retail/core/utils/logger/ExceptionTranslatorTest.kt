package com.dispensesure.retail.core.utils.logger

import android.database.sqlite.SQLiteException
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Unit tests for [ExceptionTranslator]. Runs under Robolectric because [SQLiteException] is an
 * Android framework class not present on the plain JVM stub jar (same reasoning as
 * [PerformanceLoggerTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExceptionTranslatorTest {

    @Test
    fun `translates SocketTimeoutException`() {
        assertEquals(
            "The request timed out while communicating with the server.",
            ExceptionTranslator.translate(SocketTimeoutException("timeout"))
        )
    }

    @Test
    fun `translates UnknownHostException`() {
        assertEquals(
            "Unable to reach the server. Check the network connection.",
            ExceptionTranslator.translate(UnknownHostException("no host"))
        )
    }

    @Test
    fun `translates ConnectException`() {
        assertEquals(
            "Unable to establish a connection with the server.",
            ExceptionTranslator.translate(ConnectException("refused"))
        )
    }

    @Test
    fun `translates HttpException with status code`() {
        val response = Response.error<String>(
            404,
            "not found".toResponseBody("text/plain".toMediaType())
        )
        assertEquals(
            "The server returned an unexpected response (HTTP 404).",
            ExceptionTranslator.translate(HttpException(response))
        )
    }

    @Test
    fun `translates FileNotFoundException before generic IOException`() {
        assertEquals(
            "The requested file could not be found.",
            ExceptionTranslator.translate(FileNotFoundException("missing.txt"))
        )
    }

    @Test
    fun `translates generic IOException`() {
        assertEquals(
            "An input/output error occurred.",
            ExceptionTranslator.translate(IOException("disk error"))
        )
    }

    @Test
    fun `translates JsonSyntaxException`() {
        assertEquals(
            "The server response could not be parsed.",
            ExceptionTranslator.translate(JsonSyntaxException("bad json"))
        )
    }

    @Test
    fun `translates SerializationException`() {
        assertEquals(
            "Failed to process the application data.",
            ExceptionTranslator.translate(SerializationException("bad payload"))
        )
    }

    @Test
    fun `translates SQLiteException`() {
        assertEquals(
            "A local database error occurred.",
            ExceptionTranslator.translate(SQLiteException("db error"))
        )
    }

    @Test
    fun `translates SecurityException`() {
        assertEquals(
            "The application does not have permission to perform this operation.",
            ExceptionTranslator.translate(SecurityException("denied"))
        )
    }

    @Test
    fun `translates OutOfMemoryError`() {
        assertEquals(
            "The application ran out of memory while performing this operation.",
            ExceptionTranslator.translate(OutOfMemoryError())
        )
    }

    @Test
    fun `translates CancellationException`() {
        assertEquals(
            "The operation was cancelled.",
            ExceptionTranslator.translate(CancellationException("cancelled"))
        )
    }

    @Test
    fun `translates IllegalArgumentException`() {
        assertEquals(
            "An invalid value was provided.",
            ExceptionTranslator.translate(IllegalArgumentException("bad arg"))
        )
    }

    @Test
    fun `translates IllegalStateException`() {
        assertEquals(
            "The application encountered an unexpected state.",
            ExceptionTranslator.translate(IllegalStateException("bad state"))
        )
    }

    @Test
    fun `translates NullPointerException`() {
        assertEquals(
            "A required value was missing.",
            ExceptionTranslator.translate(NullPointerException())
        )
    }

    @Test
    fun `falls back to generic message for unrecognized exception`() {
        class CustomAppException(message: String) : Exception(message)

        assertEquals(
            "An unexpected error occurred.",
            ExceptionTranslator.translate(CustomAppException("something custom"))
        )
    }
}
