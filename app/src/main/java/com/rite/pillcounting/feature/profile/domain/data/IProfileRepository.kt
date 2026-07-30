package com.rite.pillcounting.feature.profile.domain.data

import com.rite.pillcounting.feature.profile.domain.model.PharmacyTypeResponse
import com.rite.pillcounting.feature.profile.domain.model.Country
import com.rite.pillcounting.feature.profile.domain.model.ProfileDeleteResponse
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateRequest
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateResponse

/**
 * Contract for profile-related operations.
 */
interface IProfileRepository {

    /**
     * Updates the user's profile information on the server.
     *
     * @param request Profile update request body.
     * @return [Result] containing [ProfileUpdateResponse] or an exception on failure.
     */
    suspend fun updateProfile(
        request: ProfileUpdateRequest
    ): Result<ProfileUpdateResponse>

    /**
     * Deletes the user's profile from the server.
     *
     * @return [Result] containing [ProfileDeleteResponse] or an exception on failure.
     */
    suspend fun deleteProfile(): Result<ProfileDeleteResponse>

    /**
     * Fetches the list of selectable pharmacy types from the server.
     *
     * @return [Result] containing [PharmacyTypeResponse] or an exception on failure.
     */
    suspend fun getPharmacyTypes(): Result<PharmacyTypeResponse>

    /**
     * Fetches the reference list of countries and their states/provinces from the server.
     *
     * @return [Result] containing the list of [Country] or an exception on failure.
     */
    suspend fun getCountries(): Result<List<Country>>
}
