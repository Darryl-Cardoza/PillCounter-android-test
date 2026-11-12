package com.dispensesure.retail.core.audit.read

import com.dispensesure.retail.core.audit.model.AuditRecord
import com.dispensesure.retail.core.audit.model.AuditResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Turns an audit record into the boxed text shown in the viewer and the export. */
object AuditRecordFormatter {
    private const val NO_VALUE = "—"
    private val timestampFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS 'UTC'").withZone(ZoneOffset.UTC)

    fun format(record: AuditRecord): String {
        val resultLabel = if (record.result == AuditResult.SUCCESS) "✔ SUCCESS" else "✖ FAILURE"
        val time = timestampFormatter.format(Instant.parse(record.timestamp))
        val dataText = record.data.entries
            .joinToString("  ") { (fieldName, fieldValue) -> "$fieldName=${valueText(fieldValue)}" }
            .ifEmpty { NO_VALUE }
        return buildString {
            appendLine("┌ $time   #${record.seq}   ${record.tag}   $resultLabel")
            appendLine("│ session   ${record.sessionId}")
            appendLine("│ user      ${(record.userId ?: record.email).orNoValue()}   operator  ${operatorText(record)}")
            appendLine("│ device    ${record.deviceKey.orNoValue()}   terminal  ${record.terminalId.orNoValue()}   app  ${record.appVersion}")
            appendLine("│ data      $dataText")
            append("└──")
        }
    }

    // "Asha Rao (#7)", the name or id alone when only one is known, or a dash.
    private fun operatorText(record: AuditRecord): String {
        val operatorProfileIdText = record.operatorProfileId?.let { "(#$it)" }
        return listOfNotNull(record.operatorName?.takeIf { it.isNotBlank() }, operatorProfileIdText)
            .joinToString(" ")
            .orNoValue()
    }

    private fun valueText(value: JsonElement): String = when (value) {
        is JsonNull -> NO_VALUE
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString(", ", prefix = "[", postfix = "]") { valueText(it) }
        is JsonObject -> value.entries.joinToString(", ", prefix = "{", postfix = "}") { (fieldName, fieldValue) -> "$fieldName=${valueText(fieldValue)}" }
    }

    private fun String?.orNoValue(): String = if (isNullOrBlank()) NO_VALUE else this
}
