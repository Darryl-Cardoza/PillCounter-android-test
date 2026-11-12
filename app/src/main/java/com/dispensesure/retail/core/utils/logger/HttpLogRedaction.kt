package com.dispensesure.retail.core.utils.logger

/** What network logs (logcat and Chucker) must never show: these headers, and the values of these JSON fields. */
object HttpLogRedaction {
    val SENSITIVE_HEADERS = listOf("Authorization", "X-Server-Key")
    private val SENSITIVE_JSON_FIELDS = listOf("access_token", "refresh_token", "token", "key_material", "dek", "otp", "pin")

    // "field": "text" or "field": 1234. Exact names only, so dek_key_id or device_key are kept.
    private val SENSITIVE_FIELD_VALUE = Regex(
        "\"(${SENSITIVE_JSON_FIELDS.joinToString("|")})\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]\\s]+)"
    )

    fun redact(text: String): String =
        text.replace(SENSITIVE_FIELD_VALUE) { match -> "\"${match.groupValues[1]}\":\"***\"" }
}
