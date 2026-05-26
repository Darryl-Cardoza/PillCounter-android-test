package com.rite.pillcounting.feature.dispenseFlow.domain.data

import com.rite.pillcounting.feature.dispenseFlow.domain.model.DrugInfo
import com.rite.pillcounting.feature.dispenseFlow.domain.model.GetNdcRequestModel


/**
 * Defines the contract for accessing drug data.
 * This interfaceDetail abstracts the data source, allowing for flexible implementations
 * (e.g., remote API, local database) and easier testing.
 */
interface IDrugRepository {

    /**
     * Retrieves drug information for a given National Drug Code (NDC).
     *
     * @param ndc The NDC of the drug to look up.
     * @return A [DrugInfo] object containing the drug's details, or null if not found.
     * @throws Exception if there is a network error or the API call fails.
     */
    suspend fun getDrugInfoByNdc(getNdcRequestModel: GetNdcRequestModel): DrugInfo?
}