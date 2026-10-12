package com.dispensesure.retail.core.utils.common

import com.dispensesure.retail.core.utils.logger.AppLogger

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

// Finds an NDC digit group such as (?<ndc>\d{8,11}) or (?<ndc>\d{10}).
private val NDC_DIGITS_GROUP = Regex("""(?i)\(\?<(ndc\w*)>\\d\{(\d+)(?:,(\d+))?\}\)""")

fun parseScanData(template: String, actualValue: String): ParsedScanData {
    return try {
        // Dashes are allowed between NDC digits (e.g. 72888-008-00); the template's digit count still applies.
        val regex = Regex(NDC_DIGITS_GROUP.replace(template) {
            val (name, min, max) = it.destructured
            val maxDigits = max.ifEmpty { min }
            "(?<$name>\\d(?:-?\\d){${min.toInt() - 1},${maxDigits.toInt() - 1}})"
        })

        val match = regex.matchEntire(actualValue.trim())
            ?: run {
                logger.e("Scan data does not match label format: $actualValue")
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
            ndcNo = (rawMap["NDCNO"] ?: rawMap["NDC"])?.replace("-", ""),
            qty = rawMap["QTY"],
            bucket = rawMap["BUCKET"],
            rawMap = rawMap
        )
    } catch (e: Exception) {
        logger.e("Error parsing scan data, template=$template", e)
        ParsedScanData()
    }
}
