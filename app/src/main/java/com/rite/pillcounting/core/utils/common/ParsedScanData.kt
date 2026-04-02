data class ParsedScanData(
    val rxNo: String? = null,
    val ndcNo: String? = null,
    val qty: String? = null,
    val rawMap: Map<String, String> = emptyMap()
)

fun parseScanData(template: String, actualValue: String): ParsedScanData {
    return try {
        val keys = Regex("""\{(.*?)\}""")
            .findAll(template)
            .map { it.groupValues[1].trim().uppercase() }
            .toList()

        val values = actualValue
            .split('|')
            .map { it.trim() }

        if (keys.isEmpty() || values.isEmpty()) {
            return ParsedScanData()
        }

        val mappedData = keys.mapIndexedNotNull { index, key ->
            values.getOrNull(index)?.let { key to it }
        }.toMap()

        ParsedScanData(
            rxNo = mappedData["RXNO"],
            ndcNo = mappedData["NDCNO"],
            qty = mappedData["QTY"],
            rawMap = mappedData
        )
    } catch (e: Exception) {
        println("Error parsing scan data: ${e.message}")
        ParsedScanData()
    }
}