package com.dispensesure.retail.core.utils.logger

import android.database.sqlite.SQLiteException
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Central, single-source translation from raw exception types to a short, developer-readable
 * message. Used so no two call sites invent their own wording for the same exception, and so the
 * mapping only has to be extended in one place. The original exception is never discarded by this
 * translation — callers keep both [translate]'s result and the throwable itself (see [LogEntry]).
 */
object ExceptionTranslator {

    fun translate(throwable: Throwable): String = when (throwable) {
        is SocketTimeoutException -> "The request timed out while communicating with the server."
        is UnknownHostException -> "Unable to reach the server. Check the network connection."
        is ConnectException -> "Unable to establish a connection with the server."
        is HttpException -> "The server returned an unexpected response (HTTP ${throwable.code()})."
        is FileNotFoundException -> "The requested file could not be found."
        is IOException -> "An input/output error occurred."
        is JsonSyntaxException -> "The server response could not be parsed."
        is SerializationException -> "Failed to process the application data."
        is SQLiteException -> "A local database error occurred."
        is SecurityException -> "The application does not have permission to perform this operation."
        is OutOfMemoryError -> "The application ran out of memory while performing this operation."
        is CancellationException -> "The operation was cancelled."
        is IllegalArgumentException -> "An invalid value was provided."
        is IllegalStateException -> "The application encountered an unexpected state."
        is NullPointerException -> "A required value was missing."
        else -> "An unexpected error occurred."
    }
}
