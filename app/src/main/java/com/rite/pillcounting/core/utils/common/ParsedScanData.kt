import com.rite.pillcounting.core.utils.logger.AppLogger

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
            ndcNo = rawMap["NDCNO"] ?: rawMap["NDC"],
            qty = rawMap["QTY"],
            bucket = rawMap["BUCKET"],
            rawMap = rawMap
        )
    } catch (e: Exception) {
        logger.e("Error parsing scan data: ${e.message}")
        ParsedScanData()
    }
}