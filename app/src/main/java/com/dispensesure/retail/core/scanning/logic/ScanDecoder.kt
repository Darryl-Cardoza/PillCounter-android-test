package com.dispensesure.retail.core.scanning.logic

import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.region.isDin
import com.dispensesure.retail.core.scanning.domain.model.DecodedScan
import com.dispensesure.retail.core.utils.common.BarcodeDecoder
import com.dispensesure.retail.core.utils.common.DateFormats
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Turns a raw barcode into a lookup code (GTIN-14, or a Canadian 8-digit DIN) plus lot/expiry/serial. Shared by every scan screen. */
class ScanDecoder @Inject constructor(
    private val barcodeDecoder: BarcodeDecoder,
) {

    fun decode(raw: String, region: Region): DecodedScan {
        val isGs1 = barcodeDecoder.isGs1Barcode(raw)
        val trimmedCode = raw.trim()
        if (!isGs1 && region.eightDigitCodesAreDins && trimmedCode.isDin()) return dinAsScanned(trimmedCode)
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

    // A DIN isn't a GTIN: looked up as scanned, never padded.
    private fun dinAsScanned(din: String) =
        DecodedScan(isGs1 = false, gtin14 = null, lot = null, expiry = null, serial = null, lookupCode = din)

    private companion object {
        val EXPIRY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormats.EXPIRY_STORED)
    }
}
