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

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE pill_count_txn ADD COLUMN substitutedDrugId INTEGER REFERENCES drug_master(drugId) ON DELETE SET NULL"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_txn_substitutedDrugId ON pill_count_txn (substitutedDrugId)"
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `drug_master_new` (
                    `drugId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `drugName` TEXT,
                    `ndc` TEXT NOT NULL,
                    `drugType` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `gtin` TEXT,
                    `packageQty` INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `drug_master_new` (`drugId`, `drugName`, `ndc`, `drugType`, `createdAt`, `gtin`, `packageQty`)
                SELECT `drugId`, `drugName`, `ndc`, `drugType`, `createdAt`, `gtin`, `packageQty`
                FROM `drug_master`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `drug_master`")
            db.execSQL("ALTER TABLE `drug_master_new` RENAME TO `drug_master`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_drug_master_ndc` ON `drug_master` (`ndc`)")
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
