package com.dispensesure.retail.core.scanning.domain.model

import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.google.gson.Gson

/**
 * One physical bottle scanned against a dispense transaction.
 *
 * @property lotNumber Lot/batch number (GS1 AI 10), null for a non-GS1 scan.
 * @property expirationDate Expiry formatted MM-dd-yyyy (GS1 AI 17), null for a non-GS1 scan.
 * @property serialNumber Serial number (GS1 AI 21), null for a non-GS1 scan.
 * @property txnDetailsIds IDs of the `PillCountTxnDetailsEntity` rows recorded while this bottle
 *   was active. There is no stored pill count: a bottle's true count is always
 *   `SUM(pillCount) FROM pill_count_txn_details WHERE txnDetailsId IN txnDetailsIds AND
 *   isDeleted = 0`, computed live wherever it's needed — so deleting/redoing a count is
 *   automatically reflected, with no snapshot that can go stale.
 * @property scannedAt When this bottle was scanned (epoch millis).
 * @property txnId The `PillCountTxnEntity.txnId` this bottle belongs to.
 * @property barcodeImagePath Path to the barcode-scan image captured for this bottle, if any.
 */
data class BottleInfo(
    val lotNumber: String? = null,
    val expirationDate: String? = null,
    val serialNumber: String? = null,
    val txnDetailsIds: List<Long> = emptyList(),
    val scannedAt: Long = System.currentTimeMillis(),
    val txnId: Long = 0L,
    val barcodeImagePath: String? = null,
)

/** (De)serializes a [PillCountTxnEntity.bottleInfoListJson] column value. */
object BottleInfoJson {
    private val gson = Gson()
    private val logger = AppLogger.create<BottleInfo>()

    fun encode(list: List<BottleInfo>): String = gson.toJson(list)

    fun decode(json: String?): List<BottleInfo> =
        if (json.isNullOrBlank()) emptyList()
        else runCatching {
            gson.fromJson(json, Array<BottleInfo>::class.java).toList()
        }.onFailure { e ->
            logger.e("Failed to parse bottleInfoListJson, returning empty list", e, event = LogEvent.UNKNOWN_ERROR)
        }.getOrDefault(emptyList())
}
