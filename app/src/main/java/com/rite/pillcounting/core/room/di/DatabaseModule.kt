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
import com.rite.pillcounting.core.security.DatabaseKeyProvider
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

    /*private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pill_count_txn ADD COLUMN bucketId TEXT")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pill_count_txn ADD COLUMN batchId INTEGER")
        }
    }*/

    /** Provides the singleton instance of [AppDatabase]. */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        val dbKey = DatabaseKeyProvider.getOrCreateDatabaseKey(context)
        val passphrase = net.sqlcipher.database.SQLiteDatabase.getBytes(
            android.util.Base64.encodeToString(dbKey, android.util.Base64.NO_WRAP).toCharArray()
        )
        val factory = net.sqlcipher.database.SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "pill_counting_db"
        )
            .openHelperFactory(factory)  // SQLCipher encryption
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
