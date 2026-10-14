package com.dispensesure.retail.core.room.di

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.FaceEmbeddingDao
import com.dispensesure.retail.core.room.dao.FaceProfileDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.security.DatabaseKeyProvider
import com.dispensesure.retail.BuildConfig
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
            DatabaseKeyProvider.DATABASE_NAME
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

    /** Provides the [DispenseTxnDao]. */
    @Provides
    fun provideDispenseTxnDao(db: AppDatabase): DispenseTxnDao =
        db.dispenseTxnDao()

    /** Provides the [DispenseCountDao]. */
    @Provides
    fun provideDispenseCountDao(db: AppDatabase): DispenseCountDao =
        db.dispenseCountDao()

    /** Provides the [StockBatchDao]. */
    @Provides
    fun provideStockBatchDao(db: AppDatabase): StockBatchDao =
        db.stockBatchDao()

    /** Provides the [StockTxnDao]. */
    @Provides
    fun provideStockTxnDao(db: AppDatabase): StockTxnDao =
        db.stockTxnDao()

    /** Provides the [StockBottleInfoDao]. */
    @Provides
    fun provideStockBottleInfoDao(db: AppDatabase): StockBottleInfoDao =
        db.stockBottleInfoDao()

    /** Provides the [FaceProfileDao]. */
    @Provides
    fun provideFaceProfileDao(db: AppDatabase): FaceProfileDao = db.faceProfileDao()

    /** Provides the [FaceEmbeddingDao]. */
    @Provides
    fun provideFaceEmbeddingDao(db: AppDatabase): FaceEmbeddingDao = db.faceEmbeddingDao()
}
