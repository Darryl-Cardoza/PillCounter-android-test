package com.rite.pillcounting.core.room.models.dtos

/**
 * A drug requested in a batch, projected from a `stock_txn` header joined to `drug_master`.
 *
 * A PMS (INR^U04) batch is pre-populated with one stock-txn header per requested drug and NO
 * `bottle_info` lines yet, so this projection is how the UI surfaces those requested drugs in the
 * Recent Counts list before anything is scanned. Once a drug is counted, its bottle lines carry the
 * real totals and this placeholder is superseded.
 */
data class RequestedDrugDto(
    val drugId: Long?,
    val ndc: String?,
    val drugName: String?,
)
