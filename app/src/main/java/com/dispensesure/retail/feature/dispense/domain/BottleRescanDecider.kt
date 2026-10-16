package com.dispensesure.retail.feature.dispense.domain

import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle

/** What a second scan of the drug being counted means. */
sealed interface RescanOutcome {
    data object AlreadyScanned : RescanOutcome
    data object AskAddNew : RescanOutcome
    data object AskReplace : RescanOutcome
}

/** Decides what a mid-count scan of the same drug is. Values in, a decision out. */
object BottleRescanDecider {

    /**
     * - The last bottle's lot, expiry and serial again (GS1 only) → already scanned.
     * - Pills counted already, or no bottle on file yet → a new bottle.
     * - Nothing counted against the last bottle → replace it.
     */
    fun decide(
        bottles: List<DispenseBottle>,
        scanned: DispenseBottle,
        isGs1: Boolean,
        countedSoFar: Int,
    ): RescanOutcome {
        val lastBottle = bottles.lastOrNull()
        if (lastBottle != null && isGs1 &&
            lastBottle.lotNumber == scanned.lotNumber &&
            lastBottle.expirationDate == scanned.expirationDate &&
            lastBottle.serialNumber == scanned.serialNumber
        ) {
            return RescanOutcome.AlreadyScanned
        }
        return if (countedSoFar > 0 || bottles.isEmpty()) RescanOutcome.AskAddNew else RescanOutcome.AskReplace
    }
}
