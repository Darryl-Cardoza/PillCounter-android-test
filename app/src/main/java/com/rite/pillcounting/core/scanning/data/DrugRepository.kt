package com.rite.pillcounting.core.scanning.data

import com.google.gson.Gson
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.core.scanning.data.remote.IDrugAPI
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.domain.model.DrugInfo
import com.rite.pillcounting.core.scanning.domain.model.GetNdcRequestModel
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of the [IDrugRepository] interfaceDetail that fetches
 * drug information from a remote backend API using [IDrugAPI].
 *
 * This repository is responsible for:
 * - Adding authentication headers (Bearer token from [PreferenceHelper]).
 * - Executing the network request via Retrofit.
 * - Safely handling errors (HTTP, network, unexpected).
 * - Mapping the raw API response into the [DrugInfo] domain model.
 *
 * ### Error Handling
 * - If the access token is missing, the request will not be sent and `null` is returned.
 * - `HttpException` (e.g., 401/403/500) is caught, logged, and results in `null`.
 * - `IOException` (network issues) is caught, logged, and results in `null`.
 * - Any other exceptions are caught and logged to prevent crashes.
 *
 * ### Return Contract
 * - Returns a [DrugInfo] object if data is successfully fetched and mapped.
 * - Returns `null` if authentication fails, no results are found, or an error occurs.
 *
 * @property api Retrofit API service for accessing drug endpoints.
 * @property preferenceHelper Helper for retrieving the saved access token.
 */
@Singleton
class DrugRepository @Inject constructor(
    private val api: IDrugAPI,
    private val preferenceHelper: PreferenceHelper,
) : IDrugRepository {

    /** Logger instance for this repository. */
    private val logger = AppLogger.create<DrugRepository>()

    /**
     * Retrieves drug information from the backend service using the given [ndc].
     *
     * - Injects the `Authorization: Bearer <token>` header automatically.
     * - Maps the backend response into a [DrugInfo] domain object.
     * - Provides null-safety and exception guarding to avoid app crashes.
     *
     * @param ndc National Drug Code of the drug to be fetched.
     * @return A [DrugInfo] object if the request is successful, otherwise `null`.
     */
    override suspend fun getDrugInfoByNdc(getNdcRequestModel: GetNdcRequestModel): DrugInfo? {
        logger.i("Fetching drug info for NDC: '$getNdcRequestModel'")

        return try {
            val token = preferenceHelper.getAccessToken()
            if (token.isNullOrBlank()) {
                logger.e("No access token found. Aborting API call.")
                return null
            }

            val response = api.getDrugInfoByNdc(
                authorization = "Bearer $token",
                getNdcRequestModel = getNdcRequestModel
            )

            val result = response.data
            if (
                result == null ||
                (getNdcRequestModel.target_ndc.isNotEmpty() &&
                        result.is_ndc_same == false &&
                        result.is_ndc_equivalent == false)
            ) {
                logger.w("No result found in API response for NDC: '$getNdcRequestModel'")
                null
            } else {
                logger.i("Returning mapped DrugInfo Result -> $response")
                logger.i("Returning mapped DrugInfo data -> ${response.data}")
                DrugInfo(
                    brandName = result.scanned_ndc?.manufacturer ?: "N/A",
                    genericName = result.scanned_ndc?.lookup_name ?: "N/A",
                    ndc = result.scanned_ndc?.drug_code ?: "N/A",
                    is_ndc_equivalent = result.is_ndc_equivalent,
                    drugType = result.scanned_ndc?.regulatory?.schedule.toString(),
                    qty = result.scanned_ndc?.`package`?.stock_qty
                        ?: result.scanned_ndc?.`package`?.levels?.firstOrNull()?.contains?.quantity,
                    isHazardous = result.scanned_ndc?.is_hazardous,
                    strength = result.scanned_ndc?.strength_info?.display
                        ?: result.scanned_ndc?.active_ingredients?.firstOrNull()?.strength,
                    dosageForm = result.scanned_ndc?.dosage_form?.firstOrNull()
                ).also {
                    logger.i("Returning mapped DrugInfo -> $it")
                }
            }
        } catch (e: HttpException) {
            logger.e(
                "HTTP error while fetching drug info (code=${e.code()}, message=${e.message()})",
                e
            )
            null
        } catch (e: IOException) {
            logger.e("Network error while fetching drug info", e)
            null
        } catch (e: Exception) {
            logger.e("Unexpected error while fetching drug info", e)
            null
        }
    }
}