package com.dispensesure.retail.core.room

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDatabaseTest {

    private lateinit var appDatabase: AppDatabase
    private lateinit var sqliteDatabase: SupportSQLiteDatabase

    @Before
    fun setUp() {
        appDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        sqliteDatabase = appDatabase.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        appDatabase.close()
    }

    @Test
    fun `database opens at version 8`() {
        assertEquals(8, sqliteDatabase.version)
    }

    @Test
    fun `drug_master has a region column and a unique ndc-region index`() {
        assertTrue("region" in columnNamesOf(tableName = "drug_master"))
        val indexNames = schemaObjectNames(objectType = "index")
        assertTrue("index_drug_master_ndc_region" in indexNames)
        assertFalse("index_drug_master_ndc" in indexNames)
    }

    @Test
    fun `v6 table names replace the v5 ones`() {
        val tableNames = schemaObjectNames(objectType = "table")

        assertTrue(
            tableNames.containsAll(
                listOf("dispense_txn", "dispense_count", "stock_batch", "stock_txn", "stock_bottle_info", "drug_master")
            )
        )
        listOf("pill_count_txn", "pill_count_txn_details", "batch", "bottle_info").forEach { v5TableName ->
            assertFalse("$v5TableName should not exist in v6", v5TableName in tableNames)
        }
    }

    @Test
    fun `v6 index names follow the renamed tables`() {
        val indexNames = schemaObjectNames(objectType = "index")

        assertTrue(
            indexNames.containsAll(
                listOf(
                    "idx_dispense_txn_userLocalId",
                    "idx_dispense_txn_drugId",
                    "idx_dispense_txn_substitutedDrugId",
                    "idx_dispense_count_txnId",
                    "idx_stock_bottle_info_stockTxnId",
                    "idx_stock_bottle_info_batchId",
                    "idx_stock_txn_drugId",
                    "idx_stock_txn_batchId",
                )
            )
        )
    }

    @Test
    fun `dispense_txn has the renamed columns and no isDispense`() {
        val columnNames = columnNamesOf(tableName = "dispense_txn")

        assertTrue(columnNames.containsAll(listOf("userLocalId", "bottlesJson")))
        listOf("isDispense", "localId", "bottleInfoListJson").forEach { v5ColumnName ->
            assertFalse("$v5ColumnName should not exist in v6", v5ColumnName in columnNames)
        }
    }

    @Test
    fun `dispense_count has the renamed columns`() {
        val columnNames = columnNamesOf(tableName = "dispense_count")

        assertTrue(columnNames.containsAll(listOf("countId", "step")))
        listOf("txnDetailsId", "type").forEach { v5ColumnName ->
            assertFalse("$v5ColumnName should not exist in v6", v5ColumnName in columnNames)
        }
    }

    @Test
    fun `every DAO answers on an empty database`() = runTest {
        assertNull(appDatabase.dispenseTxnDao().getById(1L))
        assertEquals(0, appDatabase.dispenseCountDao().getTotalPillCountForTxn(1L))
        assertEquals(0L, appDatabase.stockBatchDao().getActiveCount())
        assertTrue(appDatabase.stockTxnDao().getByBatchId(1L).isEmpty())
        assertNull(appDatabase.stockBottleInfoDao().getById(1L))
        assertNull(appDatabase.drugMasterDao().getDrugById(1L))
        assertNull(appDatabase.userDao().getByUserId("nobody"))
    }

    // Names of every table or index SQLite holds for this database.
    private fun schemaObjectNames(objectType: String): List<String> =
        sqliteDatabase.query("SELECT name FROM sqlite_master WHERE type = '$objectType'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun columnNamesOf(tableName: String): List<String> =
        sqliteDatabase.query("PRAGMA table_info($tableName)").use { cursor ->
            val nameColumnIndex = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameColumnIndex)) }
        }
}
