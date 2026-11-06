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
}
