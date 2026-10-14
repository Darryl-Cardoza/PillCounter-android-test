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
 * @property countIds IDs of the `DispenseCountEntity` rows recorded while this bottle
 *   was active. There is no stored pill count: a bottle's true count is always
 *   `SUM(pillCount) FROM dispense_count WHERE countId IN countIds`,
 *   computed live wherever it's needed — so deleting/redoing a count is
 *   automatically reflected, with no snapshot that can go stale.
 * @property scannedAt When this bottle was scanned (epoch millis).
 * @property txnId The `DispenseTxnEntity.txnId` this bottle belongs to.
 * @property barcodeImagePath Path to the barcode-scan image captured for this bottle, if any.
 */
data class DispenseBottle(
    val lotNumber: String? = null,
    val expirationDate: String? = null,
    val serialNumber: String? = null,
    val countIds: List<Long> = emptyList(),
    val scannedAt: Long = System.currentTimeMillis(),
    val txnId: Long = 0L,
    val barcodeImagePath: String? = null,
)

/** (De)serializes a [DispenseTxnEntity.bottlesJson] column value. */
object DispenseBottleJson {
    private val gson = Gson()
    private val logger = AppLogger.create<DispenseBottle>()

    fun encode(list: List<DispenseBottle>): String = gson.toJson(list)

    fun decode(json: String?): List<DispenseBottle> =
        if (json.isNullOrBlank()) emptyList()
        else runCatching {
            gson.fromJson(json, Array<DispenseBottle>::class.java).toList()
        }.onFailure { e ->
            logger.e("Failed to parse bottlesJson, returning empty list", e, event = LogEvent.PARSING_ERROR)
        }.getOrDefault(emptyList())
}
