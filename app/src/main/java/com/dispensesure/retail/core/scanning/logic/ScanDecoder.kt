package com.dispensesure.retail.core.scanning.logic

import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.scanning.domain.model.DecodedScan
import com.dispensesure.retail.core.utils.common.BarcodeDecoder
import com.dispensesure.retail.core.utils.common.DateFormats
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Turns a raw barcode into a lookup code (GTIN-14, or a Canadian plain code) plus lot/expiry/serial. Shared by every scan screen. */
class ScanDecoder @Inject constructor(
    private val barcodeDecoder: BarcodeDecoder,
) {

    fun decode(raw: String, region: Region): DecodedScan {
        val isGs1 = barcodeDecoder.isGs1Barcode(raw)
        if (!isGs1 && !region.padsShortBarcodes) return decodePlainCodeAsScanned(raw, region)
        val data = if (isGs1) barcodeDecoder.decode(raw) else null
        val extractedGtin = if (isGs1) data?.gtin else barcodeDecoder.toGtin14(raw)
        return DecodedScan(
            isGs1 = isGs1,
            gtin14 = extractedGtin?.let { barcodeDecoder.toGtin14(it) },
            lot = data?.lotNumber,
            expiry = data?.expirationDate?.format(EXPIRY_FORMAT),
            serial = data?.serialNumber,
        )
    }

    // No padding: a plain code (e.g. an 8-digit DIN) is looked up exactly as scanned.
    private fun decodePlainCodeAsScanned(raw: String, region: Region): DecodedScan {
        val scannedCode = raw.trim().takeIf { trimmedCode ->
            trimmedCode.length in region.plainProductCodeLengths && trimmedCode.all { it.isDigit() }
        }
        return DecodedScan(
            isGs1 = false,
            gtin14 = scannedCode?.takeIf { it.length == 14 },
            lot = null,
            expiry = null,
            serial = null,
            lookupCode = scannedCode,
        )
    }

    private companion object {
        val EXPIRY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormats.EXPIRY_STORED)
    }
}
