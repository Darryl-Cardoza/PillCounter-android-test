package com.dispensesure.retail.feature.dispense.data

import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import javax.inject.Inject

/** What a container scan proved against the order's drug. */
sealed interface ContainerOutcome {
    // The saved drug record matched; it is used as it is.
    data class SavedMatch(val drug: DrugMasterEntity) : ContainerOutcome

    // The server confirmed the drug. imageUrl is the server's image, for the details bar.
    data class ServerMatch(val drug: DrugMasterEntity, val imageUrl: String?) : ContainerOutcome

    // The server flagged a generic equivalent; the user confirms it first.
    data class Substitute(val drug: DrugMasterEntity, val imageUrl: String?) : ContainerOutcome

    data class Mismatch(val drug: DrugMasterEntity) : ContainerOutcome
    data object NotFound : ContainerOutcome

    // The lookup failed. DrugResolver already logged it, so callers only show the error.
    data object LookupFailed : ContainerOutcome
}

/** Checks a scanned container against the drug the order expects. */
class ContainerVerifier @Inject constructor(
    private val drugResolver: DrugResolver,
) {

    /**
     * Validation order:
     *   1. Local DB lookup. If [expectedNdc] is set and
     *      the scanned NDC matches it, we use the local entry directly.
     *   2. Server fallback. The request carries `target_drug = expectedNdc` so
     *      the server can flag substitute (generic-equivalent) drugs via
     *      `is_drug_equivalent`.
     *   3. If the server reports a substitute, we show the equivalence
     *      confirmation dialog first. The user has to
     *      accept the substitute explicitly via the SubstituteConfirmed action.
     */
    suspend fun verify(gtin14: String, expectedNdc: String?): ContainerOutcome {
        val lookup = drugResolver.resolve(
            code = gtin14,
            expectedNdc = expectedNdc,
            allowNoName = true,
            scannedGtin = gtin14.trim(),
        )
        return when (lookup) {
            is DrugLookup.Found -> outcomeOf(lookup, expectedNdc)
            DrugLookup.NoName, DrugLookup.NotFound -> ContainerOutcome.NotFound
            is DrugLookup.Failed -> ContainerOutcome.LookupFailed
        }
    }

    private fun outcomeOf(lookup: DrugLookup.Found, expectedNdc: String?): ContainerOutcome {
        val drug = lookup.drug
        if (!lookup.fromServer) return ContainerOutcome.SavedMatch(drug)
        if (lookup.isEquivalent) return ContainerOutcome.Substitute(drug, lookup.imageUrl)
        // Hard mismatch: an expected NDC is set (from HL7 or from the
        // RX label) and the server didn't flag the scan as a
        // substitute.
        if (!expectedNdc.isNullOrBlank() && drug.ndc != expectedNdc) return ContainerOutcome.Mismatch(drug)
        return ContainerOutcome.ServerMatch(drug, lookup.imageUrl)
    }
}
