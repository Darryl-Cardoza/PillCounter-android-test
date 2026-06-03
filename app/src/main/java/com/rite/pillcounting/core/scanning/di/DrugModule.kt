package com.rite.pillcounting.core.scanning.di

import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.core.scanning.data.DrugRepository
import com.rite.pillcounting.core.scanning.data.remote.IDrugAPI
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Dagger Hilt module that provides all dependencies required for the
 * **Drug Barcode Scanning** feature of the application.
 *
 * This module defines how instances of [IDrugAPI] (Retrofit API service)
 * and [IDrugRepository] (data repository) are created and shared within
 * the dependency graph.
 *
 * These dependencies enable the app to fetch and decode drug-related data
 * from external APIs (such as openFDA or internal services) using the
 * scanned barcode, and to persist relevant information locally via
 * [PreferenceHelper].
 *
 * All provided dependencies are scoped as singletons to ensure one shared
 * instance throughout the application lifecycle.
 *
 * @see IDrugAPI
 * @see IDrugRepository
 * @see DrugRepository
 */
@Module
@InstallIn(SingletonComponent::class)
object DrugModule {

    /**
     * Provides a singleton instance of the [IDrugAPI].
     *
     * This API interfaceDetail defines the Retrofit endpoints for retrieving
     * drug and medication data from remote data sources based on barcode
     * or product identifiers.
     *
     * @param retrofit The [Retrofit] instance used to generate the API service.
     * @return A concrete implementation of [IDrugAPI].
     */
    @Provides
    @Singleton
    fun provideDrugApi(
        retrofit: Retrofit
    ): IDrugAPI =
        retrofit.create(IDrugAPI::class.java)

    /**
     * Provides a singleton instance of the [IDrugRepository].
     *
     * The repository serves as the intermediary between the network layer
     * ([IDrugAPI]) and local data utilities ([PreferenceHelper]).
     * It handles business logic such as caching, transformation of API
     * responses, and coordination of barcode scan results.
     *
     * @param drugApi The [IDrugAPI] instance used for remote data access.
     * @param preferenceHelper The [PreferenceHelper] instance used for local storage and caching.
     * @return A concrete implementation of [IDrugRepository].
     */
    @Provides
    @Singleton
    fun provideDrugRepository(
        drugApi: IDrugAPI,
        preferenceHelper: PreferenceHelper
    ): IDrugRepository =
        DrugRepository(
            api = drugApi,
            preferenceHelper = preferenceHelper
        )
}