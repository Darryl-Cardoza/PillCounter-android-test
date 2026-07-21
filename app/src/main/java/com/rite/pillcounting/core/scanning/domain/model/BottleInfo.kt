package com.rite.pillcounting.core.scanning.domain.model

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
 */
data class BottleInfo(
    val lotNumber: String? = null,
    val expirationDate: String? = null,
    val serialNumber: String? = null,
    val txnDetailsIds: List<Long> = emptyList(),
    val scannedAt: Long = System.currentTimeMillis(),
)

/** (De)serializes a [PillCountTxnEntity.bottleInfoListJson] column value. */
object BottleInfoJson {
    private val gson = Gson()

    fun encode(list: List<BottleInfo>): String = gson.toJson(list)

    fun decode(json: String?): List<BottleInfo> =
        if (json.isNullOrBlank()) emptyList()
        else runCatching {
            gson.fromJson(json, Array<BottleInfo>::class.java).toList()
        }.getOrDefault(emptyList())
}
