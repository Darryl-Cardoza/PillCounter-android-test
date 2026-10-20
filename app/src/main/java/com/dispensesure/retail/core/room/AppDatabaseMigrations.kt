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
}
