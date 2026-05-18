package com.rite.pillcounting.core.room.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides the Room database and DAOs.
 *
 * Notes:
 * - Add `.addMigrations(MIGRATION_X_Y, ...)` to the builder when you introduce schema changes.
 * - Avoid destructive migrations in production unless you explicitly accept data loss.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE batch ADD COLUMN isSynced INTEGER NOT NULL DEFAULT 0")
        }
    }


    /** Provides the singleton instance of [AppDatabase]. */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "pill_counting_db"
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    /** Provides the [UserDao]. */
    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()

    /** Provides the [DrugMasterDao]. */
    @Provides
    fun provideDrugMasterDao(db: AppDatabase): DrugMasterDao = db.drugMasterDao()

    /** Provides the [PillCountTxnDao]. */
    @Provides
    fun providePillCountTxnDao(db: AppDatabase): PillCountTxnDao = db.pillCountTxnDao()

    /** Provides the [PillCountTxnDetailsDao]. */
    @Provides
    fun providePillCountTxnDetailsDao(db: AppDatabase): PillCountTxnDetailsDao = db.pillCountTxnDetailsDao()

    /** Provides the [BatchDao]. */
    @Provides
    fun provideBatchDao(db: AppDatabase): BatchDao = db.batchDao()
}
