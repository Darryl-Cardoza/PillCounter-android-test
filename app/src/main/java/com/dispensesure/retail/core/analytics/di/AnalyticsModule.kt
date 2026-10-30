package com.dispensesure.retail.core.analytics.di

import com.dispensesure.retail.BuildConfig
import com.dispensesure.retail.core.analytics.collect.AnalyticsObserver
import com.dispensesure.retail.core.analytics.collect.PmsLifecycleQuery
import com.dispensesure.retail.core.analytics.store.AnalyticsCountedDao
import com.dispensesure.retail.core.analytics.store.AnalyticsStore
import com.dispensesure.retail.core.analytics.store.DailyAnalyticsDao
import com.dispensesure.retail.core.analytics.store.SystemAnalyticsClock
import com.dispensesure.retail.core.analytics.upload.AnalyticsUploader
import com.dispensesure.retail.core.analytics.upload.IAnalyticsApi
import com.dispensesure.retail.core.events.DomainEventBus
import com.dispensesure.retail.core.faceAuth.logic.SessionLockController
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.utils.device.DeviceKeyProvider
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import retrofit2.Retrofit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AnalyticsDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AnalyticsModule {

    @Provides
    @Singleton
    @AnalyticsDispatcher
    fun provideAnalyticsDispatcher(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Provides
    @Singleton
    fun provideAnalyticsStore(
        dao: DailyAnalyticsDao,
        countedDao: AnalyticsCountedDao,
        preferenceHelper: PreferenceHelper,
        sessionLockController: SessionLockController,
        regionProvider: RegionProvider,
    ): AnalyticsStore = AnalyticsStore(
        dao = dao,
        countedDao = countedDao,
        clock = SystemAnalyticsClock,
        pharmacyUserId = { preferenceHelper.getUserId() ?: "" },
        terminalName = { preferenceHelper.getSelectedTerminalName() ?: "" },
        operatorId = {
            val faceId = sessionLockController.operatorProfileId.value?.toString()
            if (!faceId.isNullOrBlank()) faceId else preferenceHelper.getUserId() ?: ""
        },
        region = { regionProvider.current.name },
        appVersion = BuildConfig.VERSION_NAME,
    )

    @Provides
    @Singleton
    fun provideAnalyticsApi(retrofit: Retrofit): IAnalyticsApi = retrofit.create(IAnalyticsApi::class.java)

    @Provides
    @Singleton
    fun providePmsLifecycleQuery(
        dispenseTxnDao: DispenseTxnDao,
        stockBatchDao: StockBatchDao,
    ): PmsLifecycleQuery = PmsLifecycleQuery(
        dispenseTxnDao = dispenseTxnDao,
        stockBatchDao = stockBatchDao,
    )

    @Provides
    @Singleton
    fun provideAnalyticsObserver(
        bus: DomainEventBus,
        countedDao: AnalyticsCountedDao,
        dispenseTxnDao: DispenseTxnDao,
        dispenseCountDao: DispenseCountDao,
        drugDao: DrugMasterDao,
        stockTxnDao: StockTxnDao,
        stockBottleInfoDao: StockBottleInfoDao,
        stockBatchDao: StockBatchDao,
        preferenceHelper: PreferenceHelper,
        store: AnalyticsStore,
    ): AnalyticsObserver = AnalyticsObserver(
        bus = bus,
        countedDao = countedDao,
        dispenseTxnDao = dispenseTxnDao,
        dispenseCountDao = dispenseCountDao,
        drugDao = drugDao,
        stockTxnDao = stockTxnDao,
        stockBottleInfoDao = stockBottleInfoDao,
        stockBatchDao = stockBatchDao,
        isHazardousDrugEnabled = { preferenceHelper.isHazardousDrugEnabled() },
        applyAnalytics = { increments, durations, insertions ->
            store.apply(increments, durations, insertions)
        },
    )

    @Provides
    @Singleton
    fun provideAnalyticsUploader(
        store: AnalyticsStore,
        api: IAnalyticsApi,
        preferenceHelper: PreferenceHelper,
        deviceKeyProvider: DeviceKeyProvider,
        pmsLifecycleQuery: PmsLifecycleQuery,
    ): AnalyticsUploader = AnalyticsUploader(
        store = store,
        api = api,
        preferenceHelper = preferenceHelper,
        deviceKeyProvider = deviceKeyProvider,
        pmsLifecycleQuery = pmsLifecycleQuery,
    )
}
