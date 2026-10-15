package com.dispensesure.retail.core.utils.logger

/**
 * Stand-in for [source] that keeps its stack frames and (as the message) its type name, but not its
 * message or cause. Lets the logger classify and report the real type without the PHI-prone text.
 */
class MessagelessException(val source: Throwable) : Exception(source.javaClass.name) {
    init { stackTrace = source.stackTrace }
}

/**
 * A copy that keeps the exception type and stack frames but not the message, for logging where
 * the message can echo PHI (scanned labels, HL7 field values) that PhiRedactor would not catch.
 */
fun Throwable.withoutMessage(): Throwable = MessagelessException(this)

/** The throwable behind a [MessagelessException], else this. */
fun Throwable.unmasked(): Throwable = (this as? MessagelessException)?.source ?: this

// Results the repositories return for an expired or missing session; the session-expiry flow handles them.
private val SESSION_FAILURE_MESSAGES = setOf("LOGOUT", "Refresh returned 401", "No refresh token available")

/**
 * Not an app fault: a cancelled coroutine, a network failure, a 4xx (or the synthetic offline 599)
 * answer, or an expired session. [AppLogger.e] logs these at WARN so they are not shipped as ERROR.
 */
fun Throwable.isExpectedFailure(): Boolean = unmasked().let { t ->
    t is kotlinx.coroutines.CancellationException ||
        t is java.net.UnknownHostException ||
        // A bind failure (port in use) is a local fault, not a network drop.
        (t is java.net.SocketException && t !is java.net.BindException) ||
        t is java.io.InterruptedIOException ||
        (t is retrofit2.HttpException && (t.code() in 400..499 || t.code() == 599)) ||
        t.message in SESSION_FAILURE_MESSAGES
}
