package com.dispensesure.retail.core.room

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `existing drugs become US after the migration`() {
        val v6Database = openV6DrugMasterDatabase()
        v6Database.insertDrug(ndc = "00093005801")

        AppDatabaseMigrations.MIGRATION_6_7.migrate(v6Database)

        v6Database.query("SELECT region FROM drug_master WHERE ndc = '00093005801'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("US", cursor.getString(0))
        }
    }

    @Test
    fun `the same code can be saved once per region after the migration`() {
        val v6Database = openV6DrugMasterDatabase()
        v6Database.insertDrug(ndc = "00013285")
        AppDatabaseMigrations.MIGRATION_6_7.migrate(v6Database)

        v6Database.insertDrug(ndc = "00013285", region = "CA")

        val duplicateUsInsert = runCatching { v6Database.insertDrug(ndc = "00013285", region = "US") }
        assertEquals(SQLiteConstraintException::class, duplicateUsInsert.exceptionOrNull()?.let { it::class })
    }

    @Test
    fun `migrated drug_master matches a freshly created v7 drug_master`() {
        val v6Database = openV6DrugMasterDatabase()
        AppDatabaseMigrations.MIGRATION_6_7.migrate(v6Database)
        // The "fresh" database is built at the CURRENT schema version, so the migrated one must
        // be brought all the way up too — otherwise this compares v7 against the latest and
        // fails whenever a later migration touches one of these tables.
        AppDatabaseMigrations.MIGRATION_8_9.migrate(v6Database)

        val freshV7Database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val freshV7Sqlite = freshV7Database.openHelper.writableDatabase

        assertEquals(freshV7Sqlite.columnsOf("drug_master"), v6Database.columnsOf("drug_master"))
        assertEquals(freshV7Sqlite.drugMasterIndexes(), v6Database.drugMasterIndexes())
        assertEquals(freshV7Sqlite.columnsOf("stock_batch"), v6Database.columnsOf("stock_batch"))
        freshV7Database.close()
    }

    @Test
    fun `existing batches become US after the migration`() {
        val v6Database = openV6DrugMasterDatabase()
        val batchRow = ContentValues().apply {
            put("batchId", 1L)
            put("startDateTime", 1L)
            put("status", "COMPLETED")
            put("isDeleted", 0)
            put("isSynced", 0)
            put("lastAckedChunkIndex", 0)
        }
        v6Database.insert("stock_batch", SQLiteDatabase.CONFLICT_ABORT, batchRow)

        AppDatabaseMigrations.MIGRATION_6_7.migrate(v6Database)

        v6Database.query("SELECT region FROM stock_batch WHERE batchId = 1").use { cursor ->
            cursor.moveToFirst()
            assertEquals("US", cursor.getString(0))
        }
    }

    @Test
    fun `migrated dek_versions matches a freshly created v8 dek_versions`() {
        val v7Database = openEmptyDatabase(version = 7)
        AppDatabaseMigrations.MIGRATION_7_8.migrate(v7Database)

        val freshV8Database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val freshV8Sqlite = freshV8Database.openHelper.writableDatabase

        assertEquals(freshV8Sqlite.columnsOf("dek_versions"), v7Database.columnsOf("dek_versions"))
        freshV8Database.close()
    }

    @Test
    fun `migration 8 to 9 adds the PMS order reference to stock_batch`() {
        val v8Database = openEmptyDatabase(version = 8)
        v8Database.execSQL(
            "CREATE TABLE IF NOT EXISTS `stock_batch` (`batchId` INTEGER PRIMARY KEY NOT NULL, " +
                "`requestIdFromPMS` TEXT)"
        )

        AppDatabaseMigrations.MIGRATION_8_9.migrate(v8Database)

        val columnNames = v8Database.query("PRAGMA table_info(`stock_batch`)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        assertEquals(
            "migration must add orderReferenceFromPMS: $columnNames",
            true,
            "orderReferenceFromPMS" in columnNames,
        )
    }

    private fun openEmptyDatabase(version: Int): SupportSQLiteDatabase {
        val emptyCallback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val openHelperConfiguration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(emptyCallback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(openHelperConfiguration).writableDatabase
    }

    // drug_master and stock_batch exactly as schema v6 created them.
    private fun openV6DrugMasterDatabase(): SupportSQLiteDatabase {
        val v6Callback = object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drug_master` (`drugId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`drugName` TEXT, `ndc` TEXT NOT NULL, `drugType` TEXT, `createdAt` INTEGER NOT NULL, " +
                        "`gtin` TEXT, `packageQty` INTEGER, `isHazardous` INTEGER NOT NULL, `strength` TEXT, " +
                        "`dosageForm` TEXT, `drugImagePath` TEXT)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_drug_master_ndc` ON `drug_master` (`ndc`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stock_batch` (`batchId` INTEGER NOT NULL, `startDateTime` INTEGER NOT NULL, " +
                        "`endDateTime` INTEGER, `status` TEXT NOT NULL, `isDeleted` INTEGER NOT NULL, `note` TEXT, " +
                        "`bucketId` TEXT, `requestIdFromPMS` TEXT, `isSynced` INTEGER NOT NULL, " +
                        "`lastAckedChunkIndex` INTEGER NOT NULL, `totalChunks` INTEGER, `totalNdcs` INTEGER, " +
                        "`userName` TEXT, PRIMARY KEY(`batchId`))"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val openHelperConfiguration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(v6Callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(openHelperConfiguration).writableDatabase
    }

    private fun SupportSQLiteDatabase.insertDrug(ndc: String, region: String? = null) {
        val drugRow = ContentValues().apply {
            put("ndc", ndc)
            put("createdAt", 1L)
            put("isHazardous", 0)
            if (region != null) put("region", region)
        }
        insert("drug_master", SQLiteDatabase.CONFLICT_ABORT, drugRow)
    }

    // name, type, not-null and primary-key flag of each column; the default is left out on purpose.
    private fun SupportSQLiteDatabase.columnsOf(tableName: String): Set<List<Any>> =
        query("PRAGMA table_info($tableName)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(listOf(cursor.getString(1), cursor.getString(2), cursor.getInt(3), cursor.getInt(5)))
                }
            }
        }

    // Index name, unique flag and its columns in order. Skips SQLite's own autoindexes.
    private fun SupportSQLiteDatabase.drugMasterIndexes(): Set<List<Any>> {
        val indexNameAndUnique = query("PRAGMA index_list(drug_master)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1) to cursor.getInt(2))
            }
        }.filterNot { (indexName, _) -> indexName.startsWith("sqlite_autoindex") }
        return indexNameAndUnique.map { (indexName, isUnique) ->
            val indexColumns = query("PRAGMA index_info(`$indexName`)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
            }
            listOf(indexName, isUnique, indexColumns)
        }.toSet()
    }
}
