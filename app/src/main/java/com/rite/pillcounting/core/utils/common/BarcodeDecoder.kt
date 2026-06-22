package com.rite.pillcounting.core.utils.common

import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.scanning.domain.model.BarcodeData
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Injectable GS1 barcode decoder returning a structured BarcodeData model.
 *
 * Parsing is done by a left-to-right Application-Identifier (AI) walker rather
 * than independent regexes — this mirrors the iOS implementation and, crucially,
 * respects the FNC1 group separator (ASCII 0x1D) that GS1 DataMatrix/QR codes
 * embed to delimit variable-length fields.
 *
 * Why the walker (and why we no longer strip FNC1): a variable-length field such
 * as Serial (AI 21) or Lot (AI 10) ends at the next FNC1, the next fixed-length
 * AI, or end-of-string. An independent `21(...)` regex cannot know this and will
 * truncate a serial whose value happens to begin with digits that look like
 * another AI — e.g. serial "100000261686" starts with "10" (the Lot AI), so the
 * old regex captured an empty serial. The walker reads each field by its real
 * boundary instead.
 */
@Singleton
class BarcodeDecoder @Inject constructor() {

    private val logger = AppLogger.create<BarcodeDecoder>()

    /** FNC1 group separator — GS1's delimiter for variable-length fields. */
    private val gs = 29.toChar() // ASCII 0x1D (FNC1/GS)

    /** AI -> value length (in characters, excluding the AI itself). */
    private val fixedLengthAi: Map<String, Int> = buildMap {
        put("00", 18); put("01", 14); put("02", 14)
        put("11", 6); put("12", 6); put("13", 6); put("15", 6); put("16", 6); put("17", 6)
        for (p in listOf("310", "320", "330", "340")) {
            for (d in 0..4) put("$p$d", 6)
        }
    }

    /** Date AIs that, when fully present, mark the start of a new field. */
    private val dateAis = setOf("11", "13", "15", "17")

    fun decode(rawBarcode: String): BarcodeData {
        // Strip only the symbology identifier prefix (]C1, ]d2, …). Keep the FNC1
        // separators — the walker relies on them to bound variable-length fields.
        val cleaned = rawBarcode.replace(Regex("\\](?i)(c1|j1|q3|e0|d2)"), "")

        val fields = try {
            parseGs1Fields(cleaned)
        } catch (e: Exception) {
            logger.e("Failed to decode GS1: ${e.message}")
            emptyMap()
        }

        return BarcodeData(
            gtin = fields["01"],
            lotNumber = fields["10"],
            serialNumber = fields["21"],
            productionDate = DateUtils.parseBarcodeDate(fields["11"]),
            packingDate = DateUtils.parseBarcodeDate(fields["13"]),
            sellByDate = DateUtils.parseBarcodeDate(fields["15"]),
            expirationDate = DateUtils.parseBarcodeDate(fields["17"]),
            netWeightKg = weightFor(fields, "310"),
            grossWeightKg = weightFor(fields, "330"),
            netWeightLb = weightFor(fields, "320"),
            grossWeightLb = weightFor(fields, "340")
        )
    }

    /**
     * Walks the (cleaned) barcode position by position, extracting AI -> value
     * pairs. Supports both parenthesis format "(01)123…" and raw format
     * "01123…".
     */
    private fun parseGs1Fields(input: String): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        var i = 0

        while (i < input.length) {
            // Skip group separators.
            if (input[i] == gs) { i++; continue }

            // Parenthesis format: (01)12345…
            if (input[i] == '(') {
                val close = input.indexOf(')', i)
                if (close < 0) break
                val ai = input.substring(i + 1, close)
                i = close + 1
                val len = fixedLengthAi[ai]
                if (len != null) {
                    val end = minOf(i + len, input.length)
                    fields[ai] = input.substring(i, end)
                    i = end
                } else {
                    var end = i
                    while (end < input.length && input[end] != gs && input[end] != '(') end++
                    fields[ai] = input.substring(i, end)
                    i = end
                }
                continue
            }

            // Raw format: try a 4-, 3-, then 2-digit AI.
            var matched = false
            for (aiLen in intArrayOf(4, 3, 2)) {
                if (i + aiLen > input.length) continue
                val ai = input.substring(i, i + aiLen)
                if (!ai.all { it.isDigit() }) continue

                val len = fixedLengthAi[ai]
                if (len != null) {
                    val valueStart = i + aiLen
                    val valueEnd = minOf(valueStart + len, input.length)
                    fields[ai] = input.substring(valueStart, valueEnd)
                    i = valueEnd
                    matched = true
                    break
                } else if (aiLen == 2) {
                    // Variable-length 2-digit AI (e.g. 10 lot, 21 serial). Read until
                    // the next FNC1, the start of a subsequent fixed-length date/weight
                    // AI, or end-of-string. We deliberately do NOT stop at another
                    // variable AI (10/21) or at 00/01/02 — those boundaries are
                    // ambiguous and their AI digits occur freely inside serial data.
                    val valueStart = i + aiLen
                    var valueEnd = valueStart
                    while (valueEnd < input.length &&
                        input[valueEnd] != gs &&
                        !isDateOrWeightAiAt(input, valueEnd)
                    ) {
                        valueEnd++
                    }
                    fields[ai] = input.substring(valueStart, valueEnd)
                    i = valueEnd
                    matched = true
                    break
                }
            }

            if (!matched) i++
        }

        return fields
    }

    /**
     * True if a fully-formed date (AI 11/13/15/17 + 6 digits) or weight
     * (AI 310x/320x/330x/340x + 6 digits) begins at [pos]. Used to terminate a
     * variable-length field when the scanner stripped the FNC1 separator.
     */
    private fun isDateOrWeightAiAt(s: String, pos: Int): Boolean {
        if (pos + 8 <= s.length) {
            val ai = s.substring(pos, pos + 2)
            if (ai in dateAis && s.substring(pos + 2, pos + 8).all { it.isDigit() }) return true
        }
        if (pos + 10 <= s.length) {
            val ai = s.substring(pos, pos + 4)
            if (Regex("^(310|320|330|340)[0-4]$").matches(ai) &&
                s.substring(pos + 4, pos + 10).all { it.isDigit() }
            ) return true
        }
        return false
    }

    /**
     * Resolves a weight for the family [prefix] (310/320/330/340). The 4th AI
     * digit (0–4) is the number of implied decimal places.
     */
    private fun weightFor(fields: Map<String, String>, prefix: String): Double? {
        for (decimals in 0..4) {
            val value = fields["$prefix$decimals"]?.toIntOrNull() ?: continue
            return value / 10.0.pow(decimals.toDouble())
        }
        return null
    }

    fun isGs1Barcode(rawBarcode: String): Boolean {
        // GS1 symbology identifiers prepended by the scanner (Code-128, DataMatrix, QR, etc.)
        if (rawBarcode.startsWith("]C1") || rawBarcode.startsWith("]c1") ||
            rawBarcode.startsWith("]e0") || rawBarcode.startsWith("]d2") ||
            rawBarcode.startsWith("]Q3") || rawBarcode.startsWith("]J1")
        ) return true

        // FNC1 group-separator character — definitive GS1 encoding marker
        if (rawBarcode.contains(gs)) return true

        // Application identifiers expressed with parentheses: (01), (17), (10), etc.
        if (Regex("\\(\\d{2,4}\\)").containsMatchIn(rawBarcode)) return true

        // Bare GS1 format: BT scanners often strip the symbology identifier, producing
        // a raw string that starts with AI "01" immediately followed by a 14-digit GTIN.
        // e.g. "010034354768910021<serial>..." — the walker handles bare "01" AIs.
        if (rawBarcode.length >= 16 &&
            rawBarcode.startsWith("01") &&
            rawBarcode.substring(2, 16).all { it.isDigit() }
        ) return true

        return false
    }

    fun toGtin14(gtin: String?): String? {
        if (gtin.isNullOrBlank()) return null
        val digitsOnly = gtin.filter { it.isDigit() }

        return when (digitsOnly.length) {
            8 -> digitsOnly.padStart(14, '0')
            12 -> digitsOnly.padStart(14, '0')
            13 -> digitsOnly.padStart(14, '0')
            14 -> digitsOnly
            else -> null // Invalid length
        }
    }
}
