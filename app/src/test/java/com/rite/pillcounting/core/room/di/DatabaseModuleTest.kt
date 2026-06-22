package com.rite.pillcounting.core.room.di

import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.UserDao
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
        every { db.pillCountTxnDao() } returns mockk<PillCountTxnDao>(relaxed = true)
        every { db.pillCountTxnDetailsDao() } returns mockk<PillCountTxnDetailsDao>(relaxed = true)
        every { db.batchDao() } returns mockk<BatchDao>(relaxed = true)
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
    fun `providePillCountTxnDao returns non-null PillCountTxnDao`() {
        assertNotNull(DatabaseModule.providePillCountTxnDao(db))
    }

    @Test
    fun `providePillCountTxnDetailsDao returns non-null PillCountTxnDetailsDao`() {
        assertNotNull(DatabaseModule.providePillCountTxnDetailsDao(db))
    }

    @Test
    fun `provideBatchDao returns non-null BatchDao`() {
        assertNotNull(DatabaseModule.provideBatchDao(db))
    }
}
