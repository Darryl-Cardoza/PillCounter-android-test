package com.rite.pillcounting.core.room.di

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.security.DatabaseKeyProvider
import com.rite.pillcounting.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {

        val builder = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "pill_counting_db"
        )
            // Stock-count normalization (v3 → v4) restructures local tables; existing local
            // rows are disposable (synced to PMS), so recreate rather than migrate.
            .fallbackToDestructiveMigration()

        if (!BuildConfig.DEBUG) {
            // IMPORTANT
            System.loadLibrary("sqlcipher")

            val dbKey = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)
            val passphrase = Base64.encodeToString(dbKey, Base64.NO_WRAP).toByteArray(Charsets.UTF_8)
            builder.openHelperFactory(SupportOpenHelperFactory(passphrase))
        }

        return builder.build()
    }

    /** Provides the [UserDao]. */
    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()

    /** Provides the [DrugMasterDao]. */
    @Provides
    fun provideDrugMasterDao(db: AppDatabase): DrugMasterDao =
        db.drugMasterDao()

    /** Provides the [PillCountTxnDao]. */
    @Provides
    fun providePillCountTxnDao(db: AppDatabase): PillCountTxnDao =
        db.pillCountTxnDao()

    /** Provides the [PillCountTxnDetailsDao]. */
    @Provides
    fun providePillCountTxnDetailsDao(db: AppDatabase): PillCountTxnDetailsDao =
        db.pillCountTxnDetailsDao()

    /** Provides the [BatchDao]. */
    @Provides
    fun provideBatchDao(db: AppDatabase): BatchDao =
        db.batchDao()

    /** Provides the [StockTxnDao]. */
    @Provides
    fun provideStockTxnDao(db: AppDatabase): StockTxnDao =
        db.stockTxnDao()

    /** Provides the [BottleInfoDao]. */
    @Provides
    fun provideBottleInfoDao(db: AppDatabase): BottleInfoDao =
        db.bottleInfoDao()
}
