package com.dispensesure.retail.core.room.di

import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.DekVersionDao
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.UserDao
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DatabaseModule]'s `@Provides` functions.
 *
 * The DAO-providing functions are exercised against a mocked [AppDatabase]; each returns a
 * (mocked) DAO which is asserted non-null.
 *
 * SKIPPED:
 * - `provideDatabase` — calls `Room.databaseBuilder(context, ...).build()` (and conditionally
 *   loads the native `sqlcipher` library). Building a real Room database requires a real
 *   Android [android.content.Context] / SQLite runtime and is not possible on the plain JVM.
 */
class DatabaseModuleTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = mockk(relaxed = true)
        every { db.userDao() } returns mockk<UserDao>(relaxed = true)
        every { db.drugMasterDao() } returns mockk<DrugMasterDao>(relaxed = true)
        every { db.dispenseTxnDao() } returns mockk<DispenseTxnDao>(relaxed = true)
        every { db.dispenseCountDao() } returns mockk<DispenseCountDao>(relaxed = true)
        every { db.stockBatchDao() } returns mockk<StockBatchDao>(relaxed = true)
        every { db.dekVersionDao() } returns mockk<DekVersionDao>(relaxed = true)
    }

    @Test
    fun `provideUserDao returns non-null UserDao`() {
        assertNotNull(DatabaseModule.provideUserDao(db))
    }

    @Test
    fun `provideDrugMasterDao returns non-null DrugMasterDao`() {
        assertNotNull(DatabaseModule.provideDrugMasterDao(db))
    }

    @Test
    fun `provideDispenseTxnDao returns non-null DispenseTxnDao`() {
        assertNotNull(DatabaseModule.provideDispenseTxnDao(db))
    }

    @Test
    fun `provideDispenseCountDao returns non-null DispenseCountDao`() {
        assertNotNull(DatabaseModule.provideDispenseCountDao(db))
    }

    @Test
    fun `provideStockBatchDao returns non-null StockBatchDao`() {
        assertNotNull(DatabaseModule.provideStockBatchDao(db))
    }

    @Test
    fun `provideDekVersionDao returns non-null DekVersionDao`() {
        assertNotNull(DatabaseModule.provideDekVersionDao(db))
    }
}
