package com.dispensesure.retail.core.analytics.model

/**
 * The five kinds of work a daily analytics row counts separately.
 *
 * Each work type owns its own count and duration columns on the row, so a day's dispenses of
 * different kinds never pool into one average.
 */
enum class AnalyticsWorkType {
    DISPENSE_NORMAL,
    DISPENSE_CONTROLLED,
    DISPENSE_HAZARDOUS,
    INVENTORY_CYCLE_COUNT,
    INVENTORY_STOCK_COUNT;

    companion object {

        /**
         * Picks the PRIMARY work type for one completed count.
         *
         * Hazardous is a cross-cutting attribute, not a third bucket: a hazardous normal drug
         * counts as DISPENSE_NORMAL (and also increments the hazardous counter separately).
         * A hazardous controlled drug counts as DISPENSE_CONTROLLED (same).
         *
         * @param isDispense True for a dispense count, false for an inventory count.
         * @param isHazardous [com.dispensesure.retail.core.room.models.DrugMasterEntity.isHazardous]
         *   of the drug actually counted (the substitute's, when one was used).
         * @param drugType [com.dispensesure.retail.core.room.models.DrugMasterEntity.drugType];
         *   non-blank means controlled.
         * @param isPmsInitiated Inventory only: the batch carries a
         *   [com.dispensesure.retail.core.room.models.StockBatchEntity.requestIdFromPMS].
         */
        fun classify(
            isDispense: Boolean,
            isHazardous: Boolean,
            drugType: String?,
            isPmsInitiated: Boolean,
        ): AnalyticsWorkType = when {
            !isDispense -> if (isPmsInitiated) INVENTORY_CYCLE_COUNT else INVENTORY_STOCK_COUNT
            !drugType.isNullOrBlank() -> DISPENSE_CONTROLLED
            else -> DISPENSE_NORMAL
        }
    }
}
