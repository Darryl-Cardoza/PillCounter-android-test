package com.rite.pillcounting.core.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.di.BatchConverters
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.BottleInfoEntity
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.StockTxnEntity
import com.rite.pillcounting.core.room.models.UserEntity
import com.rite.pillcounting.core.security.SecureStringConverter

/**
 * Main Room database for the application.
 *
 * This database defines all the core entities and their DAOs used across
 * the pill counting application.
 *
 * ### Entities:
 * - [UserEntity] → Represents application users.
 * - [DrugMasterEntity] → Master list of drugs with NDC and metadata.
 * - [PillCountTxnEntity] → Pill count transaction headers (parent records).
 * - [PillCountTxnDetailsEntity] → Pill count transaction details (child records).
 * - [BatchEntity] → Batch headers that group multiple transactions together.
 *
 * ### Usage:
 * Obtain an instance of [AppDatabase] via `Room.databaseBuilder()` and use
 * the exposed DAO getters to interact with persistent data.
 *
 * ### Notes:
 * - Increase the [android.R.attr.version] number and provide a migration strategy when making
 *   schema changes.
 * - `exportSchema = true` ensures schema history is exported for versioning.
 */
@Database(
    entities = [
        UserEntity::class,
        DrugMasterEntity::class,
        PillCountTxnEntity::class,
        PillCountTxnDetailsEntity::class,
        BatchEntity::class,
        StockTxnEntity::class,
        BottleInfoEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(
    SecureStringConverter::class,
    BatchConverters::class
)
abstract class AppDatabase : RoomDatabase() {

    /** DAO for accessing [UserEntity] records. */
    abstract fun userDao(): UserDao

    /** DAO for managing [DrugMasterEntity] records. */
    abstract fun drugMasterDao(): DrugMasterDao

    /** DAO for managing [PillCountTxnEntity] transaction headers. */
    abstract fun pillCountTxnDao(): PillCountTxnDao

    /** DAO for managing [PillCountTxnDetailsEntity] transaction details. */
    abstract fun pillCountTxnDetailsDao(): PillCountTxnDetailsDao

    /** DAO for managing [BatchEntity] batch headers. */
    abstract fun batchDao(): BatchDao

    /** DAO for managing [StockTxnEntity] stock transaction headers. */
    abstract fun stockTxnDao(): StockTxnDao

    /** DAO for managing [BottleInfoEntity] stock bottle lines. */
    abstract fun bottleInfoDao(): BottleInfoDao

    companion object {
        /**
         * v2 → v3: add `strength` and `dosageForm` columns to `drug_master`.
         * Existing rows get NULL; values are backfilled as drugs are re-scanned.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drug_master ADD COLUMN strength TEXT")
                db.execSQL("ALTER TABLE drug_master ADD COLUMN dosageForm TEXT")
            }
        }

        /**
         * v5 → v6: add `drugImagePath` column to `drug_master`.
         * Existing rows get NULL; the path is populated on next scan when the API
         * returns an image URL and [DrugImageDownloader] saves it locally.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drug_master ADD COLUMN drugImagePath TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create temporary table with new schema (isDispense instead of countType)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pill_count_txn_new` (
                        `txnId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `localId` INTEGER, 
                        `drugId` INTEGER, 
                        `isDispense` INTEGER NOT NULL, 
                        `targetCount` INTEGER, 
                        `status` TEXT NOT NULL, 
                        `note` TEXT, 
                        `barcodeImage` TEXT, 
                        `isSubstitute` INTEGER NOT NULL, 
                        `rxNo` TEXT, 
                        `refillNo` TEXT, 
                        `patientName` TEXT, 
                        `isDeleted` INTEGER NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        `isComingFromHL7` INTEGER, 
                        `isSynced` INTEGER, 
                        `isNdcVerified` INTEGER, 
                        `bucketId` TEXT, 
                        `substitutedDrugId` INTEGER, 
                        `workflowStep` TEXT, 
                        `priority` TEXT, 
                        `isGlovesPresent` INTEGER NOT NULL, 
                        `hazardousTrayDetected` INTEGER, 
                        FOREIGN KEY(`localId`) REFERENCES `users`(`localId`) ON UPDATE NO ACTION ON DELETE SET NULL , 
                        FOREIGN KEY(`drugId`) REFERENCES `drug_master`(`drugId`) ON UPDATE NO ACTION ON DELETE SET NULL , 
                        FOREIGN KEY(`substitutedDrugId`) REFERENCES `drug_master`(`drugId`) ON UPDATE NO ACTION ON DELETE SET NULL 
                    )
                """.trimIndent())

                // 2. Copy data, mapping countType ('FIXED' -> 1, 'REGULAR' -> 0) to isDispense
                db.execSQL("""
                    INSERT INTO `pill_count_txn_new` (
                        `txnId`, `localId`, `drugId`, `isDispense`, `targetCount`, `status`, `note`, `barcodeImage`, 
                        `isSubstitute`, `rxNo`, `refillNo`, `patientName`, `isDeleted`, `createdAt`, `updatedAt`, 
                        `isComingFromHL7`, `isSynced`, `isNdcVerified`, `bucketId`, `substitutedDrugId`, `workflowStep`, 
                        `priority`, `isGlovesPresent`, `hazardousTrayDetected`
                    )
                    SELECT 
                        `txnId`, `localId`, `drugId`, 
                        CASE WHEN `countType` = 'FIXED' THEN 1 ELSE 0 END, 
                        `targetCount`, `status`, `note`, `barcodeImage`, 
                        `isSubstitute`, `rxNo`, `refillNo`, `patientName`, `isDeleted`, `createdAt`, `updatedAt`, 
                        `isComingFromHL7`, `isSynced`, `isNdcVerified`, `bucketId`, `substitutedDrugId`, `workflowStep`, 
                        `priority`, `isGlovesPresent`, `hazardousTrayDetected`
                    FROM `pill_count_txn`
                """.trimIndent())

                // 3. Drop the old table
                db.execSQL("DROP TABLE `pill_count_txn`")

                // 4. Rename the temporary table to old table name
                db.execSQL("ALTER TABLE `pill_count_txn_new` RENAME TO `pill_count_txn`")

                // 5. Recreate indexes
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_txn_localId` ON `pill_count_txn` (`localId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_txn_drugId` ON `pill_count_txn` (`drugId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_txn_substitutedDrugId` ON `pill_count_txn` (`substitutedDrugId`)")
            }
        }
    }
}
