package com.dispensesure.retail.core.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AppDatabaseMigrations {

    /** v6 → v7: drugs and stock batches get a region. Everything saved before this was US. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `drug_master` ADD COLUMN `region` TEXT NOT NULL DEFAULT 'US'")
            db.execSQL("DROP INDEX IF EXISTS `index_drug_master_ndc`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_drug_master_ndc_region` ON `drug_master` (`ndc`, `region`)"
            )
            db.execSQL("ALTER TABLE `stock_batch` ADD COLUMN `region` TEXT NOT NULL DEFAULT 'US'")
        }
    }

    /** v7 → v8: adds `dek_versions`. No existing table changes. */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `dek_versions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`dek_key_id` TEXT NOT NULL, `version` INTEGER NOT NULL, `wrapped_dek` BLOB NOT NULL, " +
                    "`kek_key_id` TEXT NOT NULL, `kek_version` INTEGER NOT NULL, `kek_algorithm` TEXT NOT NULL, " +
                    "`is_active` INTEGER NOT NULL, `is_registered` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)"
            )
        }
    }

    /** Adds ZDR-15 storage so a native inventory response can echo the PMS's own request id. */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `stock_batch` ADD COLUMN `orderReferenceFromPMS` TEXT")
        }
    }

    /** v9 → v10: adds `daily_analytics` and `analytics_counted`. No existing table changes. */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `daily_analytics` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`pharmacyUserId` TEXT NOT NULL, `terminalName` TEXT NOT NULL, `operatorId` TEXT NOT NULL, " +
                    "`businessDate` TEXT NOT NULL, `region` TEXT NOT NULL, `appVersion` TEXT NOT NULL, " +
                    "`timezoneOffsetMin` INTEGER NOT NULL, `uploadAttempts` INTEGER NOT NULL, " +
                    "`nextAttemptAt` INTEGER NOT NULL, `dispenseNormalCount` INTEGER NOT NULL, " +
                    "`dispenseNormalDurationSamples` INTEGER NOT NULL, `dispenseNormalDurationSum` INTEGER NOT NULL, " +
                    "`dispenseNormalDurationMin` INTEGER NOT NULL, `dispenseNormalDurationMax` INTEGER NOT NULL, " +
                    "`dispenseControlledCount` INTEGER NOT NULL, `dispenseControlledDurationSamples` INTEGER NOT NULL, " +
                    "`dispenseControlledDurationSum` INTEGER NOT NULL, `dispenseControlledDurationMin` INTEGER NOT NULL, " +
                    "`dispenseControlledDurationMax` INTEGER NOT NULL, `dispenseHazardousCount` INTEGER NOT NULL, " +
                    "`dispenseHazardousDurationSamples` INTEGER NOT NULL, `dispenseHazardousDurationSum` INTEGER NOT NULL, " +
                    "`dispenseHazardousDurationMin` INTEGER NOT NULL, `dispenseHazardousDurationMax` INTEGER NOT NULL, " +
                    "`inventoryCycleCount` INTEGER NOT NULL, `inventoryCycleDurationSamples` INTEGER NOT NULL, " +
                    "`inventoryCycleDurationSum` INTEGER NOT NULL, `inventoryCycleDurationMin` INTEGER NOT NULL, " +
                    "`inventoryCycleDurationMax` INTEGER NOT NULL, `inventoryCycleSealedBottlesScanned` INTEGER NOT NULL, " +
                    "`inventoryCycleNdcs` TEXT, `inventoryStockCount` INTEGER NOT NULL, " +
                    "`inventoryStockDurationSamples` INTEGER NOT NULL, `inventoryStockDurationSum` INTEGER NOT NULL, " +
                    "`inventoryStockDurationMin` INTEGER NOT NULL, `inventoryStockDurationMax` INTEGER NOT NULL, " +
                    "`inventoryStockSealedBottlesScanned` INTEGER NOT NULL, `inventoryStockNdcs` TEXT, " +
                    "`originManualTransactions` INTEGER NOT NULL, `originRxLabelProceeded` INTEGER NOT NULL, " +
                    "`originOpenPill` INTEGER NOT NULL, `safetyDrugCodeVerified` INTEGER NOT NULL, " +
                    "`safetyWrongDrugBlocked` INTEGER NOT NULL, `safetySubstituteFlagged` INTEGER NOT NULL, " +
                    "`expiryExpiredShown` INTEGER NOT NULL, " +
                    "`expiryBeforeDosageEnd` INTEGER NOT NULL, `expiryNearShown` INTEGER NOT NULL, " +
                    "`complianceGlovesDetected` INTEGER NOT NULL, `complianceGlovesNotDetected` INTEGER NOT NULL, " +
                    "`complianceHazardousTrayDetected` INTEGER NOT NULL, `accuracyCountMismatch` INTEGER NOT NULL, " +
                    "`accuracyVarianceAbsSum` INTEGER NOT NULL, `accuracyBackcountPerformed` INTEGER NOT NULL, " +
                    "`accuracyBackcountMismatch` INTEGER NOT NULL, `accuracyRecountAttemptsSum` INTEGER NOT NULL, " +
                    "`accuracyManualEntry` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_daily_analytics_pharmacyUserId_terminalName_operatorId_businessDate_region` " +
                    "ON `daily_analytics` (`pharmacyUserId`, `terminalName`, `operatorId`, `businessDate`, `region`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_daily_analytics_businessDate` " +
                    "ON `daily_analytics` (`businessDate`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `analytics_counted` (`kind` TEXT NOT NULL, " +
                    "`entityId` INTEGER NOT NULL, `businessDate` TEXT NOT NULL, " +
                    "`countedSealedQty` INTEGER NOT NULL, `countedLooseLines` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`kind`, `entityId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_analytics_counted_businessDate` " +
                    "ON `analytics_counted` (`businessDate`)"
            )
        }
    }
}
