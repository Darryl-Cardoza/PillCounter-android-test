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

// Finds an NDC digit group such as (?<ndc>\d{8,11}), (?<ndc>\d{10}) or (?<ndc>\d{8,}).
private val NDC_DIGITS_GROUP = Regex("""(?i)\(\?<(ndc\w*)>\\d\{(\d+)(,(\d*))?\}\)""")

fun parseScanData(template: String, actualValue: String): ParsedScanData {
    return try {
        // Dashes are allowed between NDC digits (e.g. 72888-008-00); the template's digit count still applies.
        val regex = Regex(NDC_DIGITS_GROUP.replace(template) {
            val (name, min, rangePart, max) = it.destructured
            // A zero-digit minimum has no first digit to anchor on, so the group is left as written.
            if (min.toInt() == 0) return@replace it.value
            val maxDigits = if (rangePart.isEmpty()) min else max
            val maxRepeats = maxDigits.toIntOrNull()?.minus(1)?.toString().orEmpty()
            "(?<$name>\\d(?:-?\\d){${min.toInt() - 1},$maxRepeats})"
        })

        val match = regex.matchEntire(actualValue.trim())
            ?: run {
                logger.w("Scan data does not match label format", event = LogEvent.SCAN_FAILED)
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
            ndcNo = (rawMap["NDCNO"] ?: rawMap["NDC"])?.let(::toElevenDigitNdc),
            qty = rawMap["QTY"],
            bucket = rawMap["BUCKET"],
            rawMap = rawMap
        )
    } catch (e: Exception) {
        logger.e("Error parsing scan data", e, event = LogEvent.SCAN_FAILED)
        ParsedScanData()
    }
}

// Pads a dashed 10-digit NDC (4-4-2, 5-3-2, 5-4-1) to 11 digits (5-4-2).
// Any other NDC just has its dashes removed.
private fun toElevenDigitNdc(ndc: String): String {
    val segments = ndc.split("-")
    if (segments.size != 3) return ndc.replace("-", "")
    val (labeler, product, pkg) = segments
    return when (segments.map { it.length }) {
        listOf(4, 4, 2) -> "0$labeler$product$pkg"
        listOf(5, 3, 2) -> "${labeler}0$product$pkg"
        listOf(5, 4, 1) -> "$labeler${product}0$pkg"
        else -> segments.joinToString("")
    }
}
