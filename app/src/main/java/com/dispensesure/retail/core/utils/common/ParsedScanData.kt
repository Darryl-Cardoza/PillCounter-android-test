package com.dispensesure.retail.core.utils.common

import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent

private val logger = AppLogger("ParsedScanData")

data class ParsedScanData(
    val rxNo: String? = null,
    val refillNo: String? = null,
    val ndcNo: String? = null,
    val qty: String? = null,
    val bucket: String? = null,
    val rawMap: Map<String, String> = emptyMap()
)

private val NAMED_GROUP_NAME = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")

fun parseScanData(template: String, actualValue: String): ParsedScanData {
    return try {
        val regex = Regex(template)

        val input = actualValue.trim()
        val match = regex.matchEntire(input)
            ?: run {
                logger.e(
                    "Scan data does not match label format ${describeMismatch(regex, input)}, " +
                        "template=$template"
                )
                return ParsedScanData()
            }

        val groupNames = NAMED_GROUP_NAME.findAll(template)
            .map { it.groupValues[1] }
            .toList()

        val rawMap = groupNames.mapNotNull { name ->
            match.groups[name]?.value?.trim()?.let { name.uppercase() to it }
        }.toMap()

        ParsedScanData(
            rxNo = rawMap["RXNO"] ?: rawMap["RXNUMBER"],
            refillNo = rawMap["REFILLNO"],
            ndcNo = rawMap["NDCNO"] ?: rawMap["NDC"],
            qty = rawMap["QTY"],
            bucket = rawMap["BUCKET"],
            rawMap = rawMap
        )
    } catch (e: Exception) {
        logger.e("Error parsing scan data, template=$template", event = LogEvent.SCAN_FAILED)
        logger.e("Error parsing scan data, template=$template", e)
        ParsedScanData()
    }
}

/**
 * Describes where [input] stops fitting [regex], for logging a failed label parse.
 * "»" marks the break point; control characters show as \xNN.
 *
 * Example Usage:
 * describeMismatch(Regex("""\d+\|\d{11}"""), "12|0071015523") // at index 13 (input ended early): 12|0071015523»
 */
internal fun describeMismatch(regex: Regex, input: String): String {
    val at = failureIndex(regex, input)
    val reason = if (at >= input.length) "input ended early" else "unexpected '${input[at].toString().visible()}'"
    return "at index $at ($reason): ${input.substring(0, at).visible()}»${input.substring(at).visible()}"
}

// Longest prefix that still fits: it matches, or the regex hit its end wanting more input.
private fun failureIndex(regex: Regex, input: String): Int {
    val matcher = regex.toPattern().matcher("")
    for (end in input.length downTo 0) {
        matcher.reset(input.substring(0, end))
        if (matcher.matches() || matcher.hitEnd()) return end
    }
    return 0
}

private fun String.visible(): String = buildString {
    for (c in this@visible) append(if (c.code < 32 || c.code == 127) "\\x%02X".format(c.code) else c)
}
