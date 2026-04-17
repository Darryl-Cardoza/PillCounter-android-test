package com.rite.pillcounting.core.utils.common

import android.util.Log
import com.rite.pillcounting.feature.barcodeScan.domain.model.BarcodeData
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Injectable GS1 barcode decoder returning a structured BarcodeData model.
 * Uses DateUtils for robust date parsing and formatting.
 */
@Singleton
class BarcodeDecoder @Inject constructor() {

    private val regexPatterns = mapOf(
        "GTIN" to Regex("(?:\\(01\\)|01)(\\d{13,14})"),
        "LotNumber" to Regex("(?<=\u001D|17\\d{6})10([A-Za-z0-9]{1,20})(?=\u001D|(11|13|15|17|21|310\\d|320\\d|330\\d|340\\d)|$)"),
        "SerialNumber" to Regex("21([^\\u001D]*?)(?=\\u001D|(01|10|11|13|15|17|21|310\\d|320\\d|330\\d|340\\d)|$)"),
        "ProductionDate" to Regex("(?:\\(11\\)|11)(\\d{6})"),
        "PackingDate" to Regex("(?:\\(13\\)|13)(\\d{6})"),
        "SellByDate" to Regex("(?:\\(15\\)|15)(\\d{6})"),
        "ExpirationDate" to Regex("(?:\\(17\\)|17)(\\d{6})"),
        "NetWeightKgs" to Regex("(?:\\(310[0-4]\\)|310[0-4])(\\d{6})"),
        "GrossWeightKgs" to Regex("(?:\\(330[0-4]\\)|330[0-4])(\\d{6})"),
        "NetWeightPounds" to Regex("(?:\\(320[0-4]\\)|320[0-4])(\\d{6})"),
        "GrossWeightPounds" to Regex("(?:\\(340[0-4]\\)|340[0-4])(\\d{6})")
    )

    fun decode(rawBarcode: String): BarcodeData {
        val cleaned = rawBarcode.replace(Regex("\\](?i)(c1|j1|q3|e0|d2)"), "").replace("\u001D", "")

        var gtin: String? = null
        var lot: String? = null
        var serial: String? = null
        var prodDate: LocalDate? = null
        var packDate: LocalDate? = null
        var sellBy: LocalDate? = null
        var expiry: LocalDate? = null
        var netKg: Double? = null
        var grossKg: Double? = null
        var netLb: Double? = null
        var grossLb: Double? = null

        try {
            regexPatterns["GTIN"]?.find(cleaned)?.let { gtin = it.groupValues[1] }
            regexPatterns["LotNumber"]?.find(cleaned)?.let { lot = it.groupValues[1] }
            regexPatterns["SerialNumber"]?.find(cleaned)?.let { serial = it.groupValues[1] }

            prodDate = parseDate(cleaned, "ProductionDate")
            packDate = parseDate(cleaned, "PackingDate")
            sellBy = parseDate(cleaned, "SellByDate")
            expiry = parseDate(cleaned, "ExpirationDate")

            netKg = parseWeight(cleaned, "NetWeightKgs")
            grossKg = parseWeight(cleaned, "GrossWeightKgs")
            netLb = parseWeight(cleaned, "NetWeightPounds")
            grossLb = parseWeight(cleaned, "GrossWeightPounds")
        } catch (e: Exception) {
            Log.e("BarcodeDecoder", "Failed to decode GS1: ${e.message}")
        }

        return BarcodeData(
            gtin = gtin,
            lotNumber = lot,
            serialNumber = serial,
            productionDate = prodDate,
            packingDate = packDate,
            sellByDate = sellBy,
            expirationDate = expiry,
            netWeightKg = netKg,
            grossWeightKg = grossKg,
            netWeightLb = netLb,
            grossWeightLb = grossLb
        )
    }

    private fun parseDate(barcode: String, key: String): LocalDate? {
        val pattern = regexPatterns[key] ?: return null
        val raw = pattern.find(barcode)?.groupValues?.get(1) ?: return null
        return DateUtils.parseBarcodeDate(raw)
    }

    private fun parseWeight(barcode: String, key: String): Double? {
        val pattern = regexPatterns[key] ?: return null
        val match = pattern.find(barcode) ?: return null
        val identifier = match.value.take(4)
        val value = match.groups[1]?.value?.toIntOrNull() ?: return null
        val decimals = identifier.last().digitToIntOrNull() ?: 0
        val divisor = 10.0.pow(decimals.toDouble())
        return value / divisor
    }

    fun isGs1Barcode(rawBarcode: String): Boolean {
        // GS1 symbology identifiers prepended by the scanner (Code-128, DataMatrix, QR, etc.)
        if (rawBarcode.startsWith("]C1") || rawBarcode.startsWith("]c1") ||
            rawBarcode.startsWith("]e0") || rawBarcode.startsWith("]d2") ||
            rawBarcode.startsWith("]Q3") || rawBarcode.startsWith("]J1")
        ) return true

        // FNC1 group-separator character — definitive GS1 encoding marker
        if (rawBarcode.contains('\u001D')) return true

        // Application identifiers expressed with parentheses: (01), (17), (10), etc.
        if (Regex("\\(\\d{2,4}\\)").containsMatchIn(rawBarcode)) return true

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
