package com.dispensesure.retail.core.utils.logger

/**
 * A copy that keeps the exception type and stack frames but not the message, for logging where
 * the message can echo PHI (scanned labels, HL7 field values) that PhiRedactor would not catch.
 */
fun Throwable.withoutMessage(): Throwable = Exception(javaClass.name).also { it.stackTrace = stackTrace }

// Results the repositories return for an expired or missing session; the session-expiry flow handles them.
private val SESSION_FAILURE_MESSAGES = setOf("LOGOUT", "Refresh returned 401", "No refresh token available")

/**
 * Not an app fault: a cancelled coroutine, a network failure, a 4xx (or the synthetic offline 599)
 * answer, or an expired session. [AppLogger.e] logs these at WARN so they are not shipped as ERROR.
 */
fun Throwable.isExpectedFailure(): Boolean =
    this is kotlinx.coroutines.CancellationException ||
        this is java.net.UnknownHostException || this is java.net.SocketException ||
        this is java.io.InterruptedIOException ||
        (this is retrofit2.HttpException && (code() in 400..499 || code() == 599)) ||
        message in SESSION_FAILURE_MESSAGES
