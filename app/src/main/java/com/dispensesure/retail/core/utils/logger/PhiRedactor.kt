package com.dispensesure.retail.core.utils.logger

/**
 * Best-effort redaction of structured PHI/PII patterns (email, SSN, phone number,
 * credit-card-like digit runs) from free-text log content before it leaves the device — applied
 * by [com.dispensesure.retail.core.utils.logger.destination.RemoteLogDestination] to the fields
 * that carry developer-written text (`message`, the translated/actual error, the stack trace).
 *
 * This is a safety net, not a substitute for care at each `AppLogger` call site: it can only
 * catch identifiers that follow a recognizable structural pattern. A patient's name, address, or
 * prescription detail typed directly into a log message (e.g. "Failed to save transaction for
 * John Smith") looks like ordinary text and passes through untouched. Call sites must not
 * interpolate patient-identifying details into log messages in the first place — see the
 * `android-logging` skill's Sensitive Information rule.
 */
object PhiRedactor {

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val SSN = Regex("""\b\d{3}-\d{2}-\d{4}\b""")
    private val CREDIT_CARD = Regex("""\b(?:\d[ -]?){13,16}\d\b""")
    private val PHONE = Regex("""\b(?:\+?1[-.\s]?)?\(?\d{3}\)?[-.\s]\d{3}[-.\s]\d{4}\b""")

    /** Redacts every recognizable pattern in [text], most-specific pattern first. */
    fun redact(text: String): String =
        text
            .replace(EMAIL, "***REDACTED-EMAIL***")
            .replace(SSN, "***REDACTED-SSN***")
            .replace(CREDIT_CARD, "***REDACTED-CC***")
            .replace(PHONE, "***REDACTED-PHONE***")
}
